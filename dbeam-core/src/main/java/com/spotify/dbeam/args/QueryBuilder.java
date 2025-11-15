/*-
 * -\-\-
 * DBeam Core
 * --
 * Copyright (C) 2016 - 2019 Spotify AB
 * --
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * -/-/-
 */

package com.spotify.dbeam.args;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.Serializable;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Wrapper class for raw SQL query. */
class QueryBuilder implements Serializable {

  private static final long serialVersionUID = 35951701611L;

  private static final char SQL_STATEMENT_TERMINATOR = ';';
  private static final String DEFAULT_SELECT_CLAUSE = "SELECT *";
  private static final String DEFAULT_WHERE_CLAUSE = "WHERE 1=1";

  interface QueryBase {

    String getBaseSql();

    QueryBase withSelect(final String selectClause);
  }

  /**
   * Represents table-based query, which we have full control of.
   *
   * <p>Immutable entity.
   */
  private static class TableQueryBase implements QueryBase {

    private final String tableName;
    private final String selectClause;

    public TableQueryBase(final String tableName) {
      this(tableName, DEFAULT_SELECT_CLAUSE);
    }

    public TableQueryBase(final String tableName, final String selectClause) {
      this.tableName = tableName;
      this.selectClause = selectClause;
    }

    @Override
    public String getBaseSql() {
      return String.format("%s FROM %s %s", selectClause, tableName, DEFAULT_WHERE_CLAUSE);
    }

    @Override
    public TableQueryBase withSelect(final String selectClause) {
      return new TableQueryBase(this.tableName, selectClause);
    }

    @Override
    public int hashCode() {
      return tableName.hashCode();
    }
  }

  /**
   * Represents user-provided raw query, which we have no control of.
   *
   * <p>Immutable entity.
   */
  private static class UserQueryBase implements QueryBase {

    private final String userSqlQuery;
    private final String selectClause;

    public UserQueryBase(final String userSqlQuery) {
      this(userSqlQuery, DEFAULT_SELECT_CLAUSE);
    }

    public UserQueryBase(final String userSqlQuery, final String selectClause) {
      this.userSqlQuery = removeTrailingSymbols(userSqlQuery);
      this.selectClause = selectClause;
    }

    @Override
    public String getBaseSql() {
      return String.format(
          "%s FROM (%s) as user_sql_query %s", selectClause, userSqlQuery, DEFAULT_WHERE_CLAUSE);
    }

    @Override
    public UserQueryBase withSelect(String selectClause) {
      return new UserQueryBase(this.userSqlQuery, selectClause);
    }

    @Override
    public int hashCode() {
      return userSqlQuery.hashCode();
    }
  }

  private final QueryBase base;
  private final List<String> whereConditions;
  private final Optional<String> limitStr;
  private final Optional<ImmutableSet<String>> excludedColumns;

  private QueryBuilder(final QueryBase base) {
    this.base = base;
    this.limitStr = Optional.empty();
    this.whereConditions = ImmutableList.of();
    this.excludedColumns = Optional.empty();
  }

  private QueryBuilder(
      final QueryBase base,
      final List<String> whereConditions,
      final Optional<String> limitStr,
      final Optional<ImmutableSet<String>> excludedColumns) {
    this.base = base;
    this.whereConditions = whereConditions;
    this.limitStr = limitStr;
    this.excludedColumns = excludedColumns;
  }

  public static QueryBuilder fromTablename(final String tableName) {
    return new QueryBuilder(new TableQueryBase(tableName));
  }

  public static QueryBuilder fromSqlQuery(final String sqlQuery) {
    return new QueryBuilder(new UserQueryBase(sqlQuery));
  }

  public QueryBuilder withPartitionCondition(
      final String partitionColumn, final String startPointIncl, final String endPointExcl) {
    return new QueryBuilder(
        this.base,
        Stream.concat(
                this.whereConditions.stream(),
                Stream.of(
                    createSqlPartitionCondition(partitionColumn, startPointIncl, endPointExcl)))
            .collect(Collectors.toList()),
        this.limitStr,
        this.excludedColumns);
  }

  public QueryBuilder withExcludedColumns(final Optional<ImmutableSet<String>> excludedColumns) {
    if (excludedColumns.isPresent() && this.base instanceof UserQueryBase) {
      UserQueryBase userQueryBase = (UserQueryBase) this.base;
      String newSqlQuery = rebuildSelectClause(userQueryBase.userSqlQuery, excludedColumns.get());
      return new QueryBuilder(
          new UserQueryBase(newSqlQuery, userQueryBase.selectClause),
          this.whereConditions,
          this.limitStr,
          excludedColumns);
    } else {
      return new QueryBuilder(this.base, this.whereConditions, this.limitStr, excludedColumns);
    }
  }

  private static String createSqlPartitionCondition(
      final String partitionColumn, final String startPointIncl, final String endPointExcl) {
    return String.format(
        " AND %s >= '%s' AND %s < '%s'",
        partitionColumn, startPointIncl, partitionColumn, endPointExcl);
  }

  public QueryBuilder withParallelizationCondition(
      final String partitionColumn,
      final long startPointIncl,
      final long endPoint,
      final boolean isEndPointExcl) {
    return new QueryBuilder(
        this.base,
        Stream.concat(
                this.whereConditions.stream(),
                Stream.of(
                    createSqlSplitCondition(
                        partitionColumn, startPointIncl, endPoint, isEndPointExcl)))
            .collect(Collectors.toList()),
        this.limitStr,
        this.excludedColumns);
  }

  private static String createSqlSplitCondition(
      final String partitionColumn,
      final long startPointIncl,
      final long endPoint,
      final boolean isEndPointExcl) {

    String upperBoundOperation = isEndPointExcl ? "<" : "<=";
    return String.format(
        " AND %s >= %s AND %s %s %s",
        partitionColumn, startPointIncl, partitionColumn, upperBoundOperation, endPoint);
  }

  /**
   * Returns generated SQL query string.
   *
   * @return generated SQL query string.
   */
  public String build() {
    String initial = base.getBaseSql();
    StringBuilder buffer = new StringBuilder(initial);
    whereConditions.forEach(buffer::append);
    limitStr.ifPresent(buffer::append);
    return buffer.toString();
  }

  private static String removeTrailingSymbols(String sqlQuery) {
    // semicolon followed by any number of delimiters at the end of the string
    String regex = String.format("%c([\\s]*)$", SQL_STATEMENT_TERMINATOR);
    return sqlQuery.replaceAll(regex, "$1");
  }

  private static String rebuildSelectClause(
      String sqlQuery, ImmutableSet<String> excludedColumns) {
    String lowerCaseQuery = sqlQuery.toLowerCase();
    int selectIdx = lowerCaseQuery.indexOf("select");
    int fromIdx = lowerCaseQuery.indexOf("from");

    if (selectIdx == -1 || fromIdx == -1 || selectIdx > fromIdx) {
      // Cannot parse, return original query
      return sqlQuery;
    }

    String selectClause = sqlQuery.substring(selectIdx + "select".length(), fromIdx).trim();
    String[] columns = selectClause.split(",");
    List<String> newColumns =
        Stream.of(columns)
            .map(String::trim)
            .filter(column -> !excludedColumns.contains(column))
            .collect(Collectors.toList());

    if (newColumns.isEmpty()) {
      return "SELECT * " + sqlQuery.substring(fromIdx);
    } else {
      return "SELECT " + String.join(", ", newColumns) + " " + sqlQuery.substring(fromIdx);
    }
  }

  public QueryBuilder withLimit(long limit) {
    return new QueryBuilder(
        this.base,
        this.whereConditions,
        Optional.of(String.format(" LIMIT %d", limit)),
        this.excludedColumns);
  }

  @Override
  public String toString() {
    return build();
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == this) {
      return true;
    }
    if (obj instanceof QueryBuilder) {
      QueryBuilder that = (QueryBuilder) obj;
      return build().equals((that.build()));
    }
    return false;
  }

  @Override
  public int hashCode() {
    return base.hashCode();
  }

  /**
   * Generates a new query to get MIN/MAX values for splitColumn.
   *
   * @param splitColumn column to use
   * @param minSplitColumnName MIN() column value alias
   * @param maxSplitColumnName MAX() column value alias
   * @return a new query builder
   */
  public QueryBuilder generateQueryToGetLimitsOfSplitColumn(
      String splitColumn, String minSplitColumnName, String maxSplitColumnName) {

    final String selectMinMax =
        String.format(
            "SELECT MIN(%s) as %s, MAX(%s) as %s",
            splitColumn, minSplitColumnName, splitColumn, maxSplitColumnName);

    return new QueryBuilder(
        base.withSelect(selectMinMax),
        this.whereConditions,
        this.limitStr,
        this.excludedColumns);
  }
}

