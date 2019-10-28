/*
 * Copyright (c) 2008-2019, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.sql.impl.calcite;

import com.google.common.collect.ImmutableList;
import com.hazelcast.spi.impl.NodeEngine;
import com.hazelcast.sql.HazelcastSqlException;
import com.hazelcast.sql.SqlErrorCode;
import com.hazelcast.sql.impl.calcite.cost.CostFactory;
import com.hazelcast.sql.impl.calcite.cost.metadata.MetadataProvider;
import com.hazelcast.sql.impl.calcite.logical.LogicalJoinRules;
import com.hazelcast.sql.impl.calcite.logical.LogicalProjectFilterRules;
import com.hazelcast.sql.impl.calcite.logical.rel.LogicalRel;
import com.hazelcast.sql.impl.calcite.logical.rel.RootLogicalRel;
import com.hazelcast.sql.impl.calcite.logical.rule.AggregateLogicalRule;
import com.hazelcast.sql.impl.calcite.logical.rule.FilterLogicalRule;
import com.hazelcast.sql.impl.calcite.logical.rule.JoinLogicalRule;
import com.hazelcast.sql.impl.calcite.logical.rule.MapScanLogicalRule;
import com.hazelcast.sql.impl.calcite.logical.rule.ProjectLogicalRule;
import com.hazelcast.sql.impl.calcite.logical.rule.SortLogicalRule;
import com.hazelcast.sql.impl.calcite.physical.distribution.DistributionTrait;
import com.hazelcast.sql.impl.calcite.physical.distribution.DistributionTraitDef;
import com.hazelcast.sql.impl.calcite.physical.rel.PhysicalRel;
import com.hazelcast.sql.impl.calcite.physical.rule.CollocatedAggregatePhysicalRule;
import com.hazelcast.sql.impl.calcite.physical.rule.FilterPhysicalRule;
import com.hazelcast.sql.impl.calcite.physical.rule.MapScanPhysicalRule;
import com.hazelcast.sql.impl.calcite.physical.rule.ProjectPhysicalRule;
import com.hazelcast.sql.impl.calcite.physical.rule.RootPhysicalRule;
import com.hazelcast.sql.impl.calcite.physical.rule.SortPhysicalRule;
import com.hazelcast.sql.impl.calcite.physical.rule.join.JoinPhysicalRule;
import com.hazelcast.sql.impl.calcite.schema.HazelcastSchema;
import com.hazelcast.sql.impl.calcite.schema.SchemaUtils;
import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.config.CalciteConnectionConfig;
import org.apache.calcite.config.CalciteConnectionConfigImpl;
import org.apache.calcite.config.CalciteConnectionProperty;
import org.apache.calcite.jdbc.HazelcastRootCalciteSchema;
import org.apache.calcite.plan.Contexts;
import org.apache.calcite.plan.ConventionTraitDef;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.volcano.AbstractConverter;
import org.apache.calcite.plan.volcano.VolcanoPlanner;
import org.apache.calcite.prepare.Prepare;
import org.apache.calcite.rel.RelCollationTraitDef;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rel.core.RelFactories;
import org.apache.calcite.rel.metadata.JaninoRelMetadataProvider;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.util.ChainedSqlOperatorTable;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql2rel.SqlToRelConverter;
import org.apache.calcite.sql2rel.StandardConvertletTable;
import org.apache.calcite.tools.Program;
import org.apache.calcite.tools.Programs;
import org.apache.calcite.tools.RuleSet;
import org.apache.calcite.tools.RuleSets;

import java.util.Properties;

/**
 * Optimizer context which holds the whole environment for the given optimization session.
 */
public class OptimizerContext {
    /** Basic Calcite config. */
    private final VolcanoPlanner planner;

    /** SQL validator. */
    private final SqlValidator validator;

    /** SQL converter. */
    private final SqlToRelConverter sqlToRelConverter;

    /**
     * Create new context for the given node engine.
     *
     * @param nodeEngine Node engine.
     * @return Context.
     */
    public static OptimizerContext create(NodeEngine nodeEngine) {
        HazelcastSchema rootSchema = SchemaUtils.createRootSchema(nodeEngine);

        JavaTypeFactory typeFactory = new HazelcastTypeFactory();
        CalciteConnectionConfig config = createConnectionConfig();
        Prepare.CatalogReader catalogReader = createCatalogReader(typeFactory, config, rootSchema);
        SqlValidator validator = createValidator(typeFactory, catalogReader);
        VolcanoPlanner planner = createPlanner(config);
        SqlToRelConverter sqlToRelConverter = createSqlToRelConverter(typeFactory, catalogReader, validator, planner);

        return new OptimizerContext(validator, sqlToRelConverter, planner);
    }

    private OptimizerContext(SqlValidator validator, SqlToRelConverter sqlToRelConverter, VolcanoPlanner planner) {
        this.validator = validator;
        this.sqlToRelConverter = sqlToRelConverter;
        this.planner = planner;
    }

    public SqlNode parse(String sql) {
        SqlNode node;

        try {
            SqlParser.ConfigBuilder parserConfig = SqlParser.configBuilder();

            parserConfig.setCaseSensitive(true);
            parserConfig.setUnquotedCasing(Casing.UNCHANGED);
            parserConfig.setQuotedCasing(Casing.UNCHANGED);
            parserConfig.setConformance(HazelcastSqlConformance.INSTANCE);

            SqlParser parser = SqlParser.create(sql, parserConfig.build());

            node = parser.parseStmt();
        } catch (Exception e) {
            throw new HazelcastSqlException(SqlErrorCode.PARSING, e.getMessage(), e);
        }

        return validator.validate(node);
    }

    public RelNode convert(SqlNode node) {
        RelRoot root = sqlToRelConverter.convertQuery(node, false, true);

        return root.rel;
    }

    public LogicalRel optimizeLogical(RelNode rel) {
        // TODO: HEP should be used here!
        RuleSet rules = RuleSets.ofList(
            // Join optimization rules.
            LogicalJoinRules.FILTER_PULL_RULE,
            LogicalJoinRules.CONDITION_PUSH_RULE,
            LogicalJoinRules.EXPRESSIONS_PUSH_RULE,

            // Filter and project rules.
            LogicalProjectFilterRules.FILTER_MERGE_RULE,
            LogicalProjectFilterRules.FILTER_PROJECT_TRANSPOSE_RULE,
            LogicalProjectFilterRules.FILTER_INTO_SCAN_RULE,
            // TODO: ProjectMergeRule: https://jira.apache.org/jira/browse/CALCITE-2223
            LogicalProjectFilterRules.PROJECT_FILTER_TRANSPOSE_RULE,
            LogicalProjectFilterRules.PROJECT_JOIN_TRANSPOSE_RULE,
            LogicalProjectFilterRules.PROJECT_REMOVE_RULE,
            LogicalProjectFilterRules.PROJECT_INTO_SCAN_RULE,


            // TODO: Aggregate rules

            // Convert Calcite node into Hazelcast nodes.
            // TODO: Should we extend converter here instead (see Flink)?
            MapScanLogicalRule.INSTANCE,
            FilterLogicalRule.INSTANCE,
            ProjectLogicalRule.INSTANCE,
            AggregateLogicalRule.INSTANCE,
            SortLogicalRule.INSTANCE,
            JoinLogicalRule.INSTANCE,

            // TODO: Transitive closures

            new AbstractConverter.ExpandConversionRule(RelFactories.LOGICAL_BUILDER)
        );

        Program program = Programs.of(rules);

        RelNode res = program.run(
            planner,
            rel,
            RuleUtils.toLogicalConvention(rel.getTraitSet()),
            ImmutableList.of(),
            ImmutableList.of()
        );

        return new RootLogicalRel(res.getCluster(), res.getTraitSet(), res);
    }

    public PhysicalRel optimizePhysical(RelNode rel) {
        RuleSet rules = RuleSets.ofList(
            SortPhysicalRule.INSTANCE,
            RootPhysicalRule.INSTANCE,
            FilterPhysicalRule.INSTANCE,
            ProjectPhysicalRule.INSTANCE,
            MapScanPhysicalRule.INSTANCE,
            CollocatedAggregatePhysicalRule.INSTANCE,
            JoinPhysicalRule.INSTANCE,

            new AbstractConverter.ExpandConversionRule(RelFactories.LOGICAL_BUILDER)
        );

        Program program = Programs.of(rules);

        RelNode res = program.run(
            planner,
            rel,
            RuleUtils.toPhysicalConvention(rel.getTraitSet(), DistributionTrait.SINGLETON_DIST),
            ImmutableList.of(),
            ImmutableList.of()
        );

        return (PhysicalRel) res;
    }

    private static CalciteConnectionConfig createConnectionConfig() {
        Properties properties = new Properties();

        properties.put(CalciteConnectionProperty.CASE_SENSITIVE.camelName(), Boolean.TRUE.toString());
        properties.put(CalciteConnectionProperty.UNQUOTED_CASING.camelName(), Casing.UNCHANGED.toString());
        properties.put(CalciteConnectionProperty.QUOTED_CASING.camelName(), Casing.UNCHANGED.toString());

        return new CalciteConnectionConfigImpl(properties);
    }

    private static Prepare.CatalogReader createCatalogReader(
        JavaTypeFactory typeFactory,
        CalciteConnectionConfig config,
        HazelcastSchema rootSchema
    ) {
        return new HazelcastCalciteCatalogReader(
            new HazelcastRootCalciteSchema(rootSchema),
            typeFactory,
            config
        );
    }

    private static SqlValidator createValidator(JavaTypeFactory typeFactory, Prepare.CatalogReader catalogReader) {
        SqlOperatorTable opTab = ChainedSqlOperatorTable.of(
            HazelcastSqlOperatorTable.instance(),
            SqlStdOperatorTable.instance()
        );

        return new HazelcastSqlValidator(
            opTab,
            catalogReader,
            typeFactory,
            HazelcastSqlConformance.INSTANCE
        );
    }

    private static VolcanoPlanner createPlanner(CalciteConnectionConfig config) {
        VolcanoPlanner planner = new VolcanoPlanner(
            CostFactory.INSTANCE,
            Contexts.of(config)
        );

        planner.clearRelTraitDefs();
        planner.addRelTraitDef(ConventionTraitDef.INSTANCE);
        planner.addRelTraitDef(DistributionTraitDef.INSTANCE);
        planner.addRelTraitDef(RelCollationTraitDef.INSTANCE);

        return planner;
    }

    private static SqlToRelConverter createSqlToRelConverter(
        JavaTypeFactory typeFactory,
        Prepare.CatalogReader catalogReader,
        SqlValidator validator,
        VolcanoPlanner planner
    ) {
        SqlToRelConverter.ConfigBuilder sqlToRelConfigBuilder = SqlToRelConverter.configBuilder()
            .withTrimUnusedFields(true)
            .withExpand(false)
            .withExplain(false)
            .withConvertTableAccess(false);

        JaninoRelMetadataProvider relMetadataProvider = JaninoRelMetadataProvider.of(MetadataProvider.INSTANCE);

        RelOptCluster cluster = RelOptCluster.create(planner, new RexBuilder(typeFactory));

        cluster.setMetadataProvider(relMetadataProvider);

        return new SqlToRelConverter(
            null,
            validator,
            catalogReader,
            cluster,
            StandardConvertletTable.INSTANCE,
            sqlToRelConfigBuilder.build()
        );
    }
}
