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
package com.dremio.exec.store.hbase;

import java.util.Set;

import org.apache.calcite.plan.RelOptRule;

import com.dremio.exec.ops.OptimizerRulesContext;
import com.dremio.exec.planner.PlannerPhase;
import com.dremio.exec.store.StoragePluginTypeRulesFactory;
import com.dremio.service.namespace.StoragePluginType;
import com.google.common.collect.ImmutableSet;

public class HBaseRulesFactory implements StoragePluginTypeRulesFactory {

  @Override
  public Set<RelOptRule> getRules(OptimizerRulesContext optimizerContext, PlannerPhase phase, StoragePluginType pluginType) {
    if(phase == PlannerPhase.PHYSICAL) {
      return ImmutableSet.<RelOptRule>of(
          HBaseScanPrule.INSTANCE,
          HBasePushFilterIntoScan.FILTER_ON_SCAN,
          HBasePushFilterIntoScan.FILTER_ON_PROJECT);
    }

    if(phase == PlannerPhase.LOGICAL) {
      return ImmutableSet.<RelOptRule>of(HBaseScanRule.INSTANCE);
    }

    return ImmutableSet.of();
  }

}
