/*
 * Copyright (C) 2017 Dremio Corporation
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
package com.dremio.exec.planner.logical;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.apache.arrow.vector.holders.BigIntHolder;
import org.apache.arrow.vector.holders.BitHolder;
import org.apache.arrow.vector.holders.DateMilliHolder;
import org.apache.arrow.vector.holders.Float4Holder;
import org.apache.arrow.vector.holders.Float8Holder;
import org.apache.arrow.vector.holders.IntHolder;
import org.apache.arrow.vector.holders.IntervalDayHolder;
import org.apache.arrow.vector.holders.IntervalYearHolder;
import org.apache.arrow.vector.holders.NullableBigIntHolder;
import org.apache.arrow.vector.holders.NullableBitHolder;
import org.apache.arrow.vector.holders.NullableDateMilliHolder;
import org.apache.arrow.vector.holders.NullableFloat4Holder;
import org.apache.arrow.vector.holders.NullableFloat8Holder;
import org.apache.arrow.vector.holders.NullableIntHolder;
import org.apache.arrow.vector.holders.NullableIntervalDayHolder;
import org.apache.arrow.vector.holders.NullableIntervalYearHolder;
import org.apache.arrow.vector.holders.NullableTimeMilliHolder;
import org.apache.arrow.vector.holders.NullableTimeStampMilliHolder;
import org.apache.arrow.vector.holders.NullableVarCharHolder;
import org.apache.arrow.vector.holders.TimeMilliHolder;
import org.apache.arrow.vector.holders.TimeStampMilliHolder;
import org.apache.arrow.vector.holders.ValueHolder;
import org.apache.arrow.vector.holders.VarCharHolder;
import org.apache.arrow.vector.util.DateUtility;
import org.apache.calcite.avatica.util.TimeUnit;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexExecutorImpl;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.schema.Schemas;
import org.apache.calcite.sql.SqlIntervalQualifier;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.util.NlsString;
import org.joda.time.DateTimeZone;
import org.joda.time.LocalDateTime;

import com.dremio.common.expression.ExpressionStringBuilder;
import com.dremio.common.expression.LogicalExpression;
import com.dremio.common.types.TypeProtos.MinorType;
import com.dremio.exec.expr.ExpressionTreeMaterializer;
import com.dremio.exec.expr.TypeHelper;
import com.dremio.exec.expr.fn.FunctionImplementationRegistry;
import com.dremio.exec.expr.fn.impl.StringFunctionHelpers;
import com.dremio.exec.expr.fn.interpreter.InterpreterEvaluator;
import com.dremio.exec.planner.physical.PlannerSettings;
import com.dremio.exec.planner.sql.TypeInferenceUtils;
import com.dremio.sabot.exec.context.FunctionContext;
import com.google.common.collect.ImmutableList;

public class ConstExecutor implements RelOptPlanner.Executor {
  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ConstExecutor.class);

  private final PlannerSettings plannerSettings;

  // This is a list of all types that cannot be folded at planning time for various reasons, most of the types are
  // currently not supported at all. The reasons for the others can be found in the evaluation code in the reduce method
  public static final List<Object> NON_REDUCIBLE_TYPES = ImmutableList.builder().add(
      // cannot represent this as a literal according to calcite
      MinorType.INTERVAL,

      // TODO - map and list are used in Dremio but currently not expressible as literals, these can however be
      // outputs of functions that take literals as inputs (such as a convert_fromJSON with a literal string
      // as input), so we need to identify functions with these return types as non-foldable until we have a
      // literal representation for them
      MinorType.MAP, MinorType.LIST,

      // TODO - DRILL-2551 - Varbinary is used in execution, but it is missing a literal definition
      // in the logical expression representation and subsequently is not supported in
      // RexToExpr and the logical expression visitors
      MinorType.VARBINARY,

      MinorType.TIMESTAMPTZ, MinorType.TIMETZ, MinorType.LATE,
      MinorType.TINYINT, MinorType.SMALLINT, MinorType.GENERIC_OBJECT, MinorType.NULL,
      MinorType.DECIMAL28DENSE, MinorType.DECIMAL38DENSE, MinorType.MONEY,
      MinorType.FIXEDBINARY, MinorType.FIXEDCHAR, MinorType.FIXED16CHAR,
      MinorType.VAR16CHAR, MinorType.UINT1, MinorType.UINT2, MinorType.UINT4,
      MinorType.UINT8)
      .build();

  final FunctionImplementationRegistry funcImplReg;
  final FunctionContext udfUtilities;
  final RexExecutorImpl calciteExecutor;

  public ConstExecutor(FunctionImplementationRegistry funcImplReg, FunctionContext udfUtilities, PlannerSettings plannerSettings) {
    this.funcImplReg = funcImplReg;
    this.udfUtilities = udfUtilities;
    this.plannerSettings = plannerSettings;
    this.calciteExecutor = new RexExecutorImpl(Schemas.createDataContext(null));
  }

  @Override
  public void reduce(RexBuilder rexBuilder, List<RexNode> constExps, List<RexNode> reducedValues) {

    // First reduce using calcite's reducer.  This is particularly important for Decimal arithmetics, where Dremio
    // does not really handle it properly.  For example, it is common to have 0.06 + 0.01 return 0.06999999999999999278355033993648248724639415740966796875
    // using Dremio, when calcite returns a Decimal of exact value 0.07.  If calcite successfully reduced the value, we should keep it and only use
    // Dremio's reduce() if calcite is unable to reduce the expressions.
    List<RexNode> reducedValuesByCalcite = new ArrayList<>();
    try {
      calciteExecutor.reduce(rexBuilder, constExps, reducedValuesByCalcite);
    } catch (Exception e) {
      logger.debug("Failed to reduce expressions using calcite executor, " + e.getMessage());
      reducedValuesByCalcite.clear();
    }
    int index = -1;
    for (RexNode newCall : constExps) {
      index++;
      if (!reducedValuesByCalcite.isEmpty() && !reducedValuesByCalcite.get(index).equals(newCall)) {
        reducedValues.add(reducedValuesByCalcite.get(index));
        continue;
      }

      // If we fail to reduce anything, catch the exception and return the same expression.
      try {
        LogicalExpression logEx = RexToExpr.toExpr(new ParseContext(plannerSettings), null /* input rowtype */, rexBuilder, newCall);
        LogicalExpression materializedExpr = ExpressionTreeMaterializer.materializeAndCheckErrors(logEx, null, funcImplReg);

        if (NON_REDUCIBLE_TYPES.contains(materializedExpr.getCompleteType().toMinorType())) {
          logger.debug("Constant expression not folded due to return type {}, complete expression: {}",
              materializedExpr.getCompleteType(),
              ExpressionStringBuilder.toString(materializedExpr));
          reducedValues.add(newCall);
          continue;
        }

        ValueHolder output = InterpreterEvaluator.evaluateConstantExpr(udfUtilities, materializedExpr);
        RelDataTypeFactory typeFactory = rexBuilder.getTypeFactory();

        if (TypeHelper.isNull(output)) {
          SqlTypeName sqlTypeName = TypeInferenceUtils.getCalciteTypeFromMinorType(materializedExpr.getCompleteType().toMinorType());
          if (sqlTypeName == null) {
            String message = String.format("Error reducing constant expression, unsupported type: %s.",
              materializedExpr.getCompleteType().toString());
            logger.error(message);
            throw new RuntimeException(message);
          }
          // Calcite does not allow "rexBuilder.makeNullLiteral(INTERVAL/MULTISET)".  Need to call custom builders for these.
          final RexNode nullLiteral;
          switch (sqlTypeName) {
            case INTERVAL_DAY:
            case INTERVAL_DAY_HOUR:
            case INTERVAL_DAY_MINUTE:
            case INTERVAL_DAY_SECOND:
            case INTERVAL_HOUR:
            case INTERVAL_HOUR_MINUTE:
            case INTERVAL_HOUR_SECOND:
            case INTERVAL_MINUTE:
            case INTERVAL_MINUTE_SECOND:
            case INTERVAL_SECOND:
              SqlIntervalQualifier dayTime = new SqlIntervalQualifier(TimeUnit.DAY, TimeUnit.SECOND, SqlParserPos.ZERO);
              nullLiteral = rexBuilder.makeCast(typeFactory.createSqlIntervalType(dayTime), rexBuilder.constantNull());
              break;
            case INTERVAL_YEAR:
            case INTERVAL_YEAR_MONTH:
            case INTERVAL_MONTH:
              SqlIntervalQualifier yearMonth = new SqlIntervalQualifier(TimeUnit.YEAR, TimeUnit.MONTH, SqlParserPos.ZERO);
              nullLiteral = rexBuilder.makeCast(typeFactory.createSqlIntervalType(yearMonth), rexBuilder.constantNull());
              break;
            case MULTISET:
              throw new RuntimeException("Got unsupported Calcite type of " + sqlTypeName);
            default:
              nullLiteral = rexBuilder.makeNullLiteral(sqlTypeName);
              break;
          }
          reducedValues.add(nullLiteral);
          continue;
        }

        switch (materializedExpr.getCompleteType().toMinorType()) {
          case INT:
            int outputInt;
            if (output instanceof IntHolder) {
              outputInt = ((IntHolder) output).value;
            } else {
              outputInt = ((NullableIntHolder) output).value;
            }
            reducedValues.add(rexBuilder.makeLiteral(
              new BigDecimal(outputInt),
              TypeInferenceUtils.createCalciteTypeWithNullability(typeFactory, SqlTypeName.INTEGER, false),
              false));
            break;
          case BIGINT:
            long outputBigint;
            if (output instanceof BigIntHolder) {
              outputBigint = ((BigIntHolder) output).value;
            } else {
              outputBigint = ((NullableBigIntHolder) output).value;
            }
            reducedValues.add(rexBuilder.makeLiteral(
              new BigDecimal(outputBigint),
              TypeInferenceUtils.createCalciteTypeWithNullability(typeFactory, SqlTypeName.BIGINT, false),
              false));
            break;
          case FLOAT4:
            float outputFloat;
            if (output instanceof Float4Holder) {
              outputFloat = ((Float4Holder) output).value;
            } else {
              outputFloat = ((NullableFloat4Holder) output).value;
            }
            reducedValues.add(rexBuilder.makeLiteral(
              new BigDecimal(outputFloat),
              TypeInferenceUtils.createCalciteTypeWithNullability(typeFactory, SqlTypeName.FLOAT, false),
              false));
            break;
          case FLOAT8:
            double outputFloat8;
            if (output instanceof Float8Holder) {
              outputFloat8 = ((Float8Holder) output).value;
            } else {
              outputFloat8 = ((NullableFloat8Holder) output).value;
            }
            reducedValues.add(rexBuilder.makeLiteral(
              new BigDecimal(outputFloat8),
              TypeInferenceUtils.createCalciteTypeWithNullability(typeFactory, SqlTypeName.DOUBLE, false),
              false));
            break;
          case VARCHAR:
            String outputString;
            if (output instanceof VarCharHolder) {
              VarCharHolder varCharHolder = (VarCharHolder) output;
              outputString = StringFunctionHelpers.toStringFromUTF8(varCharHolder.start, varCharHolder.end, varCharHolder.buffer);
            } else {
              NullableVarCharHolder nullableVarCharHolder = (NullableVarCharHolder) output;
              outputString = StringFunctionHelpers.toStringFromUTF8(nullableVarCharHolder.start, nullableVarCharHolder.end, nullableVarCharHolder.buffer);
            }
            reducedValues.add(rexBuilder.makeCharLiteral(new NlsString(outputString, null, null)));
            break;
          case BIT:
            int bitValue;
            if (output instanceof BitHolder) {
              bitValue = ((BitHolder) output).value;
            } else {
              bitValue = ((NullableBitHolder) output).value;
            }
            reducedValues.add(rexBuilder.makeLiteral(
              bitValue == 1,
              TypeInferenceUtils.createCalciteTypeWithNullability(typeFactory, SqlTypeName.BOOLEAN, false),
              false));
            break;
          case DATE:
            long dateValue;
            if (output instanceof DateMilliHolder) {
              dateValue = ((DateMilliHolder) output).value;
            } else {
              dateValue = ((NullableDateMilliHolder) output).value;
            }
            reducedValues.add(rexBuilder.makeLiteral(
                com.dremio.common.util.DateTimes.toDateTime(new LocalDateTime(dateValue, DateTimeZone.UTC)).toCalendar(null),
              TypeInferenceUtils.createCalciteTypeWithNullability(typeFactory, SqlTypeName.DATE, false),
              false));
            break;
          case TIME:
            long timeValue;
            if (output instanceof TimeMilliHolder) {
              timeValue = ((TimeMilliHolder) output).value;
            } else {
              timeValue = ((NullableTimeMilliHolder) output).value;
            }
            reducedValues.add(rexBuilder.makeLiteral(
                com.dremio.common.util.DateTimes.toDateTime(new LocalDateTime(timeValue, DateTimeZone.UTC)).toCalendar(null),
              TypeInferenceUtils.createCalciteTypeWithNullability(typeFactory, SqlTypeName.TIME, false),
              false));
            break;
          case TIMESTAMP:
            long timestampValue;
            if (output instanceof TimeStampMilliHolder) {
              timestampValue = ((TimeStampMilliHolder) output).value;
            } else {
              timestampValue = ((NullableTimeStampMilliHolder) output).value;
            }
            // Should not ignore the TIMESTAMP precision here
            reducedValues.add(rexBuilder.makeTimestampLiteral(
                com.dremio.common.util.DateTimes.toDateTime(new LocalDateTime(timestampValue, DateTimeZone.UTC)).toCalendar(null), newCall.getType().getPrecision()));
            break;
          case INTERVALYEAR:
            int yearValue;
            if (output instanceof IntervalYearHolder) {
              yearValue = ((IntervalYearHolder) output).value;
            } else {
              yearValue = ((NullableIntervalYearHolder) output).value;
            }
            reducedValues.add(rexBuilder.makeLiteral(
              new BigDecimal(yearValue),
              TypeInferenceUtils.createCalciteTypeWithNullability(typeFactory, SqlTypeName.INTERVAL_YEAR_MONTH, false),
              false));
            break;
          case INTERVALDAY:
            double dayValue;
            if (output instanceof IntervalDayHolder) {
              IntervalDayHolder intervalDayOut = (IntervalDayHolder) output;
              dayValue = ((double) intervalDayOut.days) * ((double) DateUtility.daysToStandardMillis) + (intervalDayOut.milliseconds);
            } else {
              NullableIntervalDayHolder intervalDayOut = (NullableIntervalDayHolder) output;
              dayValue = ((double) intervalDayOut.days) * ((double) DateUtility.daysToStandardMillis) + (intervalDayOut.milliseconds);
            }
            reducedValues.add(rexBuilder.makeLiteral(
              new BigDecimal(dayValue),
              TypeInferenceUtils.createCalciteTypeWithNullability(typeFactory, SqlTypeName.INTERVAL_DAY_MINUTE, false),
              false));
            break;
          // The list of known unsupported types is used to trigger this behavior of re-using the input expression
          // before the expression is even attempted to be evaluated, this is just here as a last precaution a
          // as new types may be added in the future.
          default:
            logger.debug("Constant expression not folded due to return type {}, complete expression: {}",
              materializedExpr.getCompleteType(),
              ExpressionStringBuilder.toString(materializedExpr));
            reducedValues.add(newCall);
            break;
        }
      } catch (Exception e) {
        logger.debug("Failed to reduce expression {}", newCall);
        reducedValues.add(newCall);
      }
    }

    // Apply the final cast to make sure the input and output have the same type
    for(int i=0; i<reducedValues.size(); i++) {
      final RexNode reducedExpr = reducedValues.get(i);
      final RexNode constExpr = constExps.get(i);
      if (!reducedExpr.getType().equals(constExpr.getType())) {
        reducedValues.remove(i);
        reducedValues.add(i, rexBuilder.makeCast(constExpr.getType(), reducedExpr, true /* match nullability*/));
      }
    }
  }
}


