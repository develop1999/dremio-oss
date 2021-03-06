/*
 * Copyright (C) 2017-2018 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.exec.expr;

import java.util.List;

import com.dremio.common.expression.EvaluationType;
import com.dremio.sabot.op.llvm.expr.GandivaPushdownSieve;
import com.dremio.exec.ExecConstants;
import com.dremio.options.OptionManager;
import org.apache.arrow.vector.types.pojo.ArrowType.Decimal;
import org.apache.arrow.vector.types.pojo.Field;

import com.dremio.common.expression.CompleteType;
import com.dremio.common.expression.Describer;
import com.dremio.common.expression.ErrorCollector;
import com.dremio.common.expression.ErrorCollectorImpl;
import com.dremio.common.expression.FunctionCall;
import com.dremio.common.expression.LogicalExpression;
import com.dremio.common.expression.NullExpression;
import com.dremio.common.expression.TypedNullConstant;
import com.dremio.common.expression.ValueExpressions;
import com.dremio.common.expression.fn.CastFunctions;
import com.dremio.common.expression.visitors.ConditionalExprOptimizer;
import com.dremio.common.logical.data.NamedExpression;
import com.dremio.common.types.TypeProtos.MinorType;
import com.dremio.exec.exception.SchemaChangeException;
import com.dremio.exec.expr.fn.BaseFunctionHolder;
import com.dremio.exec.expr.fn.FunctionLookupContext;
import com.dremio.exec.planner.types.RelDataTypeSystemImpl;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.record.SchemaBuilder;
import com.dremio.exec.resolver.FunctionResolver;
import com.dremio.exec.resolver.FunctionResolverFactory;
import com.google.common.collect.Lists;

public class ExpressionTreeMaterializer {

  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ExpressionTreeMaterializer.class);

  private ExpressionTreeMaterializer() {
  };

  public static LogicalExpression materialize(LogicalExpression expr, BatchSchema schema, ErrorCollector errorCollector, FunctionLookupContext functionLookupContext) {
    return ExpressionTreeMaterializer.materialize(expr, schema, errorCollector, functionLookupContext, false);
  }

  public static LogicalExpression materializeAndCheckErrors(LogicalExpression expr, BatchSchema schema, FunctionLookupContext functionLookupContext) throws SchemaChangeException {
    return materializeAndCheckErrors(expr, schema, functionLookupContext, false);
  }

  public static LogicalExpression materializeAndCheckErrors(LogicalExpression expr, BatchSchema schema, FunctionLookupContext functionLookupContext, boolean allowComplex) throws SchemaChangeException {
    ErrorCollector collector = new ErrorCollectorImpl();
    LogicalExpression e = ExpressionTreeMaterializer.materialize(expr, schema, collector, functionLookupContext, allowComplex);
    if (collector.hasErrors()) {
      throw new SchemaChangeException(String.format("Failure while trying to materialize incoming schema.  Errors:\n %s.", collector.toErrorString()));
    }
    return e;
  }

  public static Field materializeField(NamedExpression expr, BatchSchema incoming, FunctionLookupContext context){
    LogicalExpression out = materializeAndCheckErrors(expr.getExpr(), incoming, context);
    return out.getCompleteType().toField(expr.getRef());
  }

  public static SchemaBuilder materializeFields(List<NamedExpression> exprs, BatchSchema incoming, FunctionLookupContext context) {
    return materializeFields(exprs, incoming, context, false);
  }

  public static SchemaBuilder materializeFields(List<NamedExpression> exprs, BatchSchema incoming, FunctionLookupContext context, boolean allowComplex) {
    final SchemaBuilder builder = BatchSchema.newBuilder();
    for(NamedExpression e : exprs){
      CompleteType type = ExpressionTreeMaterializer.materializeAndCheckErrors(e.getExpr(), incoming, context, allowComplex).getCompleteType();
      builder.addField(type.toField(e.getRef()));
    }
    return builder;
  }

  public static LogicalExpression materialize(LogicalExpression expr, BatchSchema schema, ErrorCollector errorCollector, FunctionLookupContext functionLookupContext,
      boolean allowComplexWriterExpr) {
    return materialize(null, expr, schema, errorCollector, functionLookupContext, allowComplexWriterExpr);
  }

  public static LogicalExpression materialize(OptionManager optionManager,
                                              LogicalExpression expr,
                                              BatchSchema schema,
                                              ErrorCollector errorCollector,
                                              FunctionLookupContext functionLookupContext,
                                              boolean allowComplexWriterExpr) {
    LogicalExpression out = expr.accept(new ExpressionMaterializationVisitor(schema, errorCollector, allowComplexWriterExpr), functionLookupContext);

    if (!errorCollector.hasErrors()) {
      out = out.accept(ConditionalExprOptimizer.INSTANCE, null);
    }

    if (out instanceof NullExpression) {
      out = new TypedNullConstant(CompleteType.INT);
    }

    String strCodeGenOption = EvaluationType.CodeGenOption.Java.toString();
    if (optionManager != null) {
      strCodeGenOption = optionManager.getOption(ExecConstants.INTERNAL_EXEC_OPTION_KEY).getStringVal();
    }

    EvaluationType.CodeGenOption codeGenOption = EvaluationType.CodeGenOption.getCodeGenOption(strCodeGenOption);
    switch (codeGenOption) {
      case Gandiva:
      case GandivaOnly:
        return checkGandivaExecution(codeGenOption, schema, out);
      case Java:
      default:
        return out;
    }
  }

  private static LogicalExpression checkGandivaExecution(EvaluationType.CodeGenOption codeGenOption,
                                                         BatchSchema schema,
                                                         LogicalExpression expr) {
    GandivaPushdownSieve gandivaPushdownSieve = new GandivaPushdownSieve();
    gandivaPushdownSieve.canEvaluate(schema.getSelectionVectorMode(), expr);

    if (codeGenOption == EvaluationType.CodeGenOption.Gandiva) {
      return expr;
    }

    // Gandiva only
    if (expr.isEvaluationTypeSupported(EvaluationType.ExecutionType.GANDIVA)) {
      return expr;
    }

    // Should not reach here in production code. Gandiva only option should be used only for testing
    // The projector and filter will throw exception while running if this happens
    logger.info("Materializer returning null because codegen option is GandivaOnly and the expression cannot be evaluated in Gandiva {}", expr);
    return null;
  }

  public static LogicalExpression convertToNullableType(LogicalExpression fromExpr, MinorType toType, FunctionLookupContext functionLookupContext, ErrorCollector errorCollector) {
    String funcName = "convertToNullable" + toType.toString();
    List<LogicalExpression> args = Lists.newArrayList();
    args.add(fromExpr);
    FunctionCall funcCall = new FunctionCall(funcName, args);
    FunctionResolver resolver = FunctionResolverFactory.getResolver(funcCall);

    BaseFunctionHolder matchedConvertToNullableFuncHolder = functionLookupContext.findFunction(resolver, funcCall);
    if (matchedConvertToNullableFuncHolder == null) {
      logFunctionResolutionError(errorCollector, funcCall);
      return NullExpression.INSTANCE;
    }

    return matchedConvertToNullableFuncHolder.getExpr(funcName, args);
  }

  public static LogicalExpression addImplicitCastExact(
      LogicalExpression input,
      CompleteType toType,
      FunctionLookupContext functionLookupContext,
      ErrorCollector errorCollector) {
    return addImplicitCast(input, toType, functionLookupContext, errorCollector, true);
  }

  public static LogicalExpression addImplicitCastApproximate(
      LogicalExpression input,
      CompleteType toType,
      FunctionLookupContext functionLookupContext,
      ErrorCollector errorCollector) {
    return addImplicitCast(input, toType, functionLookupContext, errorCollector, false);
  }

  private static LogicalExpression addImplicitCast(
      LogicalExpression input,
      CompleteType toType,
      FunctionLookupContext functionLookupContext,
      ErrorCollector errorCollector,
      boolean exactResolver) {
    String castFuncName = CastFunctions.getCastFunc(toType.toMinorType());
    List<LogicalExpression> castArgs = Lists.newArrayList();
    castArgs.add(input);  //input_expr

    if(input.getCompleteType().isUnion() && toType.isUnion()) {
      return input;
    }

    // add extra parameters as necessary.
    if (toType.isVariableWidthScalar()) {
      /* We are implicitly casting to VARCHAR so we don't have a max length. */
      castArgs.add(new ValueExpressions.LongExpression(RelDataTypeSystemImpl.DEFAULT_PRECISION));

    } else if (toType.isDecimal()) {
      Decimal decimal = toType.getType(Decimal.class);
      // Add the scale and precision to the arguments of the implicit cast
      castArgs.add(new ValueExpressions.LongExpression(decimal.getPrecision()));
      castArgs.add(new ValueExpressions.LongExpression(decimal.getScale()));
    }
    // done adding extra parameters.

    final FunctionCall castCall = new FunctionCall(castFuncName, castArgs);
    final FunctionResolver resolver = exactResolver ? FunctionResolverFactory.getExactResolver(castCall) : FunctionResolverFactory.getResolver(castCall);
    final BaseFunctionHolder matchedCastFuncHolder = functionLookupContext.findFunction(resolver, castCall);

    if (matchedCastFuncHolder == null) {
      logFunctionResolutionError(errorCollector, castCall);
      return NullExpression.INSTANCE;
    }

    return matchedCastFuncHolder.getExpr(castFuncName, castArgs);
  }

  static void logFunctionResolutionError(ErrorCollector errorCollector, FunctionCall call) {
    // add error to collector
    StringBuilder sb = new StringBuilder();
    sb.append("Failure finding function: ");
    sb.append(call.getName());
    sb.append("(");
    boolean first = true;
    for(LogicalExpression e : call.args) {
      CompleteType type = e.getCompleteType();
      if (first) {
        first = false;
      } else {
        sb.append(", ");
      }
      sb.append(Describer.describe(type));
    }
    sb.append(")");

    errorCollector.addGeneralError(sb.toString());
  }
}
