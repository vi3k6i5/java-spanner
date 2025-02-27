/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.spanner.connection;

import static com.google.cloud.spanner.connection.StatementResult.ClientSideStatementType.ABORT_BATCH;
import static com.google.cloud.spanner.connection.StatementResult.ClientSideStatementType.BEGIN;
import static com.google.cloud.spanner.connection.StatementResult.ClientSideStatementType.COMMIT;
import static com.google.cloud.spanner.connection.StatementResult.ClientSideStatementType.ROLLBACK;
import static com.google.cloud.spanner.connection.StatementResult.ClientSideStatementType.RUN_BATCH;
import static com.google.cloud.spanner.connection.StatementResult.ClientSideStatementType.SET_AUTOCOMMIT;
import static com.google.cloud.spanner.connection.StatementResult.ClientSideStatementType.SET_AUTOCOMMIT_DML_MODE;
import static com.google.cloud.spanner.connection.StatementResult.ClientSideStatementType.SET_OPTIMIZER_STATISTICS_PACKAGE;
import static com.google.cloud.spanner.connection.StatementResult.ClientSideStatementType.SET_OPTIMIZER_VERSION;
import static com.google.cloud.spanner.connection.StatementResult.ClientSideStatementType.SET_READONLY;
import static com.google.cloud.spanner.connection.StatementResult.ClientSideStatementType.SET_READ_ONLY_STALENESS;
import static com.google.cloud.spanner.connection.StatementResult.ClientSideStatementType.SET_RETRY_ABORTS_INTERNALLY;
import static com.google.cloud.spanner.connection.StatementResult.ClientSideStatementType.SET_RETURN_COMMIT_STATS;
import static com.google.cloud.spanner.connection.StatementResult.ClientSideStatementType.SET_RPC_PRIORITY;
import static com.google.cloud.spanner.connection.StatementResult.ClientSideStatementType.SET_STATEMENT_TAG;
import static com.google.cloud.spanner.connection.StatementResult.ClientSideStatementType.SET_STATEMENT_TIMEOUT;
import static com.google.cloud.spanner.connection.StatementResult.ClientSideStatementType.SET_TRANSACTION_MODE;
import static com.google.cloud.spanner.connection.StatementResult.ClientSideStatementType.SET_TRANSACTION_TAG;
import static com.google.cloud.spanner.connection.StatementResult.ClientSideStatementType.SHOW_AUTOCOMMIT;
import static com.google.cloud.spanner.connection.StatementResult.ClientSideStatementType.SHOW_AUTOCOMMIT_DML_MODE;
import static com.google.cloud.spanner.connection.StatementResult.ClientSideStatementType.SHOW_COMMIT_RESPONSE;
import static com.google.cloud.spanner.connection.StatementResult.ClientSideStatementType.SHOW_COMMIT_TIMESTAMP;
import static com.google.cloud.spanner.connection.StatementResult.ClientSideStatementType.SHOW_OPTIMIZER_STATISTICS_PACKAGE;
import static com.google.cloud.spanner.connection.StatementResult.ClientSideStatementType.SHOW_OPTIMIZER_VERSION;
import static com.google.cloud.spanner.connection.StatementResult.ClientSideStatementType.SHOW_READONLY;
import static com.google.cloud.spanner.connection.StatementResult.ClientSideStatementType.SHOW_READ_ONLY_STALENESS;
import static com.google.cloud.spanner.connection.StatementResult.ClientSideStatementType.SHOW_READ_TIMESTAMP;
import static com.google.cloud.spanner.connection.StatementResult.ClientSideStatementType.SHOW_RETRY_ABORTS_INTERNALLY;
import static com.google.cloud.spanner.connection.StatementResult.ClientSideStatementType.SHOW_RETURN_COMMIT_STATS;
import static com.google.cloud.spanner.connection.StatementResult.ClientSideStatementType.SHOW_RPC_PRIORITY;
import static com.google.cloud.spanner.connection.StatementResult.ClientSideStatementType.SHOW_STATEMENT_TAG;
import static com.google.cloud.spanner.connection.StatementResult.ClientSideStatementType.SHOW_STATEMENT_TIMEOUT;
import static com.google.cloud.spanner.connection.StatementResult.ClientSideStatementType.SHOW_TRANSACTION_TAG;
import static com.google.cloud.spanner.connection.StatementResult.ClientSideStatementType.START_BATCH_DDL;
import static com.google.cloud.spanner.connection.StatementResult.ClientSideStatementType.START_BATCH_DML;
import static com.google.cloud.spanner.connection.StatementResultImpl.noResult;
import static com.google.cloud.spanner.connection.StatementResultImpl.resultSet;

import com.google.cloud.spanner.CommitResponse;
import com.google.cloud.spanner.CommitStats;
import com.google.cloud.spanner.Options.RpcPriority;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.ResultSets;
import com.google.cloud.spanner.Struct;
import com.google.cloud.spanner.TimestampBound;
import com.google.cloud.spanner.Type;
import com.google.cloud.spanner.Type.StructField;
import com.google.cloud.spanner.connection.ReadOnlyStalenessUtil.DurationValueGetter;
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Duration;
import com.google.spanner.v1.RequestOptions;
import com.google.spanner.v1.RequestOptions.Priority;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The methods in this class are called by the different {@link ClientSideStatement}s. These method
 * calls are then forwarded into a {@link Connection}.
 */
class ConnectionStatementExecutorImpl implements ConnectionStatementExecutor {
  static final class StatementTimeoutGetter implements DurationValueGetter {
    private final Connection connection;

    public StatementTimeoutGetter(Connection connection) {
      this.connection = connection;
    }

    @Override
    public long getDuration(TimeUnit unit) {
      return connection.getStatementTimeout(unit);
    }

    @Override
    public boolean hasDuration() {
      return connection.hasStatementTimeout();
    }
  }

  private static final Map<Priority, RpcPriority> validRPCPriorityValues;

  static {
    ImmutableMap.Builder<Priority, RpcPriority> builder = ImmutableMap.builder();
    builder.put(Priority.PRIORITY_HIGH, RpcPriority.HIGH);
    builder.put(Priority.PRIORITY_MEDIUM, RpcPriority.MEDIUM);
    builder.put(Priority.PRIORITY_LOW, RpcPriority.LOW);
    validRPCPriorityValues = builder.build();
  }

  /** The connection to execute the statements on. */
  private final ConnectionImpl connection;

  ConnectionStatementExecutorImpl(ConnectionImpl connection) {
    this.connection = connection;
  }

  ConnectionImpl getConnection() {
    return connection;
  }

  @Override
  public StatementResult statementSetAutocommit(Boolean autocommit) {
    Preconditions.checkNotNull(autocommit);
    getConnection().setAutocommit(autocommit);
    return noResult(SET_AUTOCOMMIT);
  }

  @Override
  public StatementResult statementShowAutocommit() {
    return resultSet("AUTOCOMMIT", getConnection().isAutocommit(), SHOW_AUTOCOMMIT);
  }

  @Override
  public StatementResult statementSetReadOnly(Boolean readOnly) {
    Preconditions.checkNotNull(readOnly);
    getConnection().setReadOnly(readOnly);
    return noResult(SET_READONLY);
  }

  @Override
  public StatementResult statementShowReadOnly() {
    return StatementResultImpl.resultSet("READONLY", getConnection().isReadOnly(), SHOW_READONLY);
  }

  @Override
  public StatementResult statementSetRetryAbortsInternally(Boolean retryAbortsInternally) {
    Preconditions.checkNotNull(retryAbortsInternally);
    getConnection().setRetryAbortsInternally(retryAbortsInternally);
    return noResult(SET_RETRY_ABORTS_INTERNALLY);
  }

  @Override
  public StatementResult statementShowRetryAbortsInternally() {
    return StatementResultImpl.resultSet(
        "RETRY_ABORTS_INTERNALLY",
        getConnection().isRetryAbortsInternally(),
        SHOW_RETRY_ABORTS_INTERNALLY);
  }

  @Override
  public StatementResult statementSetAutocommitDmlMode(AutocommitDmlMode mode) {
    getConnection().setAutocommitDmlMode(mode);
    return noResult(SET_AUTOCOMMIT_DML_MODE);
  }

  @Override
  public StatementResult statementShowAutocommitDmlMode() {
    return resultSet(
        "AUTOCOMMIT_DML_MODE", getConnection().getAutocommitDmlMode(), SHOW_AUTOCOMMIT_DML_MODE);
  }

  @Override
  public StatementResult statementSetStatementTimeout(Duration duration) {
    if (duration.getSeconds() == 0L && duration.getNanos() == 0) {
      getConnection().clearStatementTimeout();
    } else {
      TimeUnit unit =
          ReadOnlyStalenessUtil.getAppropriateTimeUnit(
              new ReadOnlyStalenessUtil.DurationGetter(duration));
      getConnection()
          .setStatementTimeout(ReadOnlyStalenessUtil.durationToUnits(duration, unit), unit);
    }
    return noResult(SET_STATEMENT_TIMEOUT);
  }

  @Override
  public StatementResult statementShowStatementTimeout() {
    return resultSet(
        "STATEMENT_TIMEOUT",
        getConnection().hasStatementTimeout()
            ? ReadOnlyStalenessUtil.durationToString(new StatementTimeoutGetter(getConnection()))
            : null,
        SHOW_STATEMENT_TIMEOUT);
  }

  @Override
  public StatementResult statementShowReadTimestamp() {
    return resultSet(
        "READ_TIMESTAMP", getConnection().getReadTimestampOrNull(), SHOW_READ_TIMESTAMP);
  }

  @Override
  public StatementResult statementShowCommitTimestamp() {
    return resultSet(
        "COMMIT_TIMESTAMP", getConnection().getCommitTimestampOrNull(), SHOW_COMMIT_TIMESTAMP);
  }

  @Override
  public StatementResult statementShowCommitResponse() {
    CommitResponse response = getConnection().getCommitResponseOrNull();
    CommitStats stats = null;
    if (response != null && response.hasCommitStats()) {
      stats = response.getCommitStats();
    }
    ResultSet resultSet =
        ResultSets.forRows(
            Type.struct(
                StructField.of("COMMIT_TIMESTAMP", Type.timestamp()),
                StructField.of("MUTATION_COUNT", Type.int64())),
            Arrays.asList(
                Struct.newBuilder()
                    .set("COMMIT_TIMESTAMP")
                    .to(response == null ? null : response.getCommitTimestamp())
                    .set("MUTATION_COUNT")
                    .to(stats == null ? null : stats.getMutationCount())
                    .build()));
    return StatementResultImpl.of(resultSet, SHOW_COMMIT_RESPONSE);
  }

  @Override
  public StatementResult statementSetReadOnlyStaleness(TimestampBound staleness) {
    getConnection().setReadOnlyStaleness(staleness);
    return noResult(SET_READ_ONLY_STALENESS);
  }

  @Override
  public StatementResult statementShowReadOnlyStaleness() {
    TimestampBound staleness = getConnection().getReadOnlyStaleness();
    return resultSet(
        "READ_ONLY_STALENESS",
        ReadOnlyStalenessUtil.timestampBoundToString(staleness),
        SHOW_READ_ONLY_STALENESS);
  }

  @Override
  public StatementResult statementSetOptimizerVersion(String optimizerVersion) {
    getConnection().setOptimizerVersion(optimizerVersion);
    return noResult(SET_OPTIMIZER_VERSION);
  }

  @Override
  public StatementResult statementShowOptimizerVersion() {
    return resultSet(
        "OPTIMIZER_VERSION", getConnection().getOptimizerVersion(), SHOW_OPTIMIZER_VERSION);
  }

  @Override
  public StatementResult statementSetOptimizerStatisticsPackage(String optimizerStatisticsPackage) {
    getConnection().setOptimizerStatisticsPackage(optimizerStatisticsPackage);
    return noResult(SET_OPTIMIZER_STATISTICS_PACKAGE);
  }

  @Override
  public StatementResult statementShowOptimizerStatisticsPackage() {
    return resultSet(
        "OPTIMIZER_STATISTICS_PACKAGE",
        getConnection().getOptimizerStatisticsPackage(),
        SHOW_OPTIMIZER_STATISTICS_PACKAGE);
  }

  @Override
  public StatementResult statementSetReturnCommitStats(Boolean returnCommitStats) {
    getConnection().setReturnCommitStats(returnCommitStats);
    return noResult(SET_RETURN_COMMIT_STATS);
  }

  @Override
  public StatementResult statementShowReturnCommitStats() {
    return resultSet(
        "RETURN_COMMIT_STATS", getConnection().isReturnCommitStats(), SHOW_RETURN_COMMIT_STATS);
  }

  @Override
  public StatementResult statementSetStatementTag(String tag) {
    getConnection().setStatementTag("".equals(tag) ? null : tag);
    return noResult(SET_STATEMENT_TAG);
  }

  @Override
  public StatementResult statementShowStatementTag() {
    return resultSet(
        "STATEMENT_TAG",
        MoreObjects.firstNonNull(getConnection().getStatementTag(), ""),
        SHOW_STATEMENT_TAG);
  }

  @Override
  public StatementResult statementSetTransactionTag(String tag) {
    getConnection().setTransactionTag("".equals(tag) ? null : tag);
    return noResult(SET_TRANSACTION_TAG);
  }

  @Override
  public StatementResult statementShowTransactionTag() {
    return resultSet(
        "TRANSACTION_TAG",
        MoreObjects.firstNonNull(getConnection().getTransactionTag(), ""),
        SHOW_TRANSACTION_TAG);
  }

  @Override
  public StatementResult statementBeginTransaction() {
    getConnection().beginTransaction();
    return noResult(BEGIN);
  }

  @Override
  public StatementResult statementCommit() {
    getConnection().commit();
    return noResult(COMMIT);
  }

  @Override
  public StatementResult statementRollback() {
    getConnection().rollback();
    return noResult(ROLLBACK);
  }

  @Override
  public StatementResult statementSetTransactionMode(TransactionMode mode) {
    getConnection().setTransactionMode(mode);
    return noResult(SET_TRANSACTION_MODE);
  }

  @Override
  public StatementResult statementStartBatchDdl() {
    getConnection().startBatchDdl();
    return noResult(START_BATCH_DDL);
  }

  @Override
  public StatementResult statementStartBatchDml() {
    getConnection().startBatchDml();
    return noResult(START_BATCH_DML);
  }

  @Override
  public StatementResult statementRunBatch() {
    long[] updateCounts = getConnection().runBatch();
    return resultSet("UPDATE_COUNTS", updateCounts, RUN_BATCH);
  }

  @Override
  public StatementResult statementAbortBatch() {
    getConnection().abortBatch();
    return noResult(ABORT_BATCH);
  }

  @Override
  public StatementResult statementSetRPCPriority(Priority priority) {
    RpcPriority value = validRPCPriorityValues.get(priority);
    getConnection().setRPCPriority(value);
    return noResult(SET_RPC_PRIORITY);
  }

  @Override
  public StatementResult statementShowRPCPriority() {
    return resultSet(
        "RPC_PRIORITY",
        getConnection().getRPCPriority() == null
            ? RequestOptions.Priority.PRIORITY_UNSPECIFIED
            : getConnection().getRPCPriority(),
        SHOW_RPC_PRIORITY);
  }
}
