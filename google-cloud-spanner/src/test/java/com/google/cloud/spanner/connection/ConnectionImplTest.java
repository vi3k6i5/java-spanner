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

import static com.google.cloud.spanner.connection.AbstractConnectionImplTest.DDL;
import static com.google.cloud.spanner.connection.AbstractConnectionImplTest.SELECT;
import static com.google.cloud.spanner.connection.AbstractConnectionImplTest.UPDATE;
import static com.google.cloud.spanner.connection.AbstractConnectionImplTest.expectSpannerException;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.core.ApiFuture;
import com.google.api.core.ApiFutures;
import com.google.api.gax.longrunning.OperationFuture;
import com.google.cloud.NoCredentials;
import com.google.cloud.Timestamp;
import com.google.cloud.spanner.CommitResponse;
import com.google.cloud.spanner.CommitStats;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.ErrorCode;
import com.google.cloud.spanner.ForwardingResultSet;
import com.google.cloud.spanner.Options;
import com.google.cloud.spanner.Options.QueryOption;
import com.google.cloud.spanner.ReadContext.QueryAnalyzeMode;
import com.google.cloud.spanner.ReadOnlyTransaction;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Spanner;
import com.google.cloud.spanner.SpannerException;
import com.google.cloud.spanner.SpannerExceptionFactory;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.TimestampBound;
import com.google.cloud.spanner.TimestampBound.Mode;
import com.google.cloud.spanner.TransactionContext;
import com.google.cloud.spanner.TransactionManager;
import com.google.cloud.spanner.TransactionRunner;
import com.google.cloud.spanner.Type;
import com.google.cloud.spanner.connection.ConnectionImpl.UnitOfWorkType;
import com.google.cloud.spanner.connection.ConnectionStatementExecutorImpl.StatementTimeoutGetter;
import com.google.cloud.spanner.connection.ReadOnlyStalenessUtil.GetExactStaleness;
import com.google.cloud.spanner.connection.StatementParser.ParsedStatement;
import com.google.cloud.spanner.connection.StatementResult.ResultType;
import com.google.cloud.spanner.connection.UnitOfWork.UnitOfWorkState;
import com.google.spanner.admin.database.v1.UpdateDatabaseDdlMetadata;
import com.google.spanner.v1.ExecuteSqlRequest.QueryOptions;
import com.google.spanner.v1.ResultSetStats;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

@RunWith(JUnit4.class)
public class ConnectionImplTest {
  public static final String URI =
      "cloudspanner:/projects/test-project-123/instances/test-instance/databases/test-database";

  static class SimpleTransactionManager implements TransactionManager {
    private TransactionState state;
    private CommitResponse commitResponse;
    private TransactionContext txContext;
    private final boolean returnCommitStats;

    private SimpleTransactionManager(TransactionContext txContext, boolean returnCommitStats) {
      this.txContext = txContext;
      this.returnCommitStats = returnCommitStats;
    }

    @Override
    public TransactionContext begin() {
      state = TransactionState.STARTED;
      return txContext;
    }

    @Override
    public void commit() {
      Timestamp commitTimestamp = Timestamp.now();
      commitResponse = mock(CommitResponse.class);
      when(commitResponse.getCommitTimestamp()).thenReturn(commitTimestamp);
      if (returnCommitStats) {
        CommitStats stats = mock(CommitStats.class);
        when(commitResponse.hasCommitStats()).thenReturn(true);
        when(stats.getMutationCount()).thenReturn(5L);
        when(commitResponse.getCommitStats()).thenReturn(stats);
      }
      state = TransactionState.COMMITTED;
    }

    @Override
    public void rollback() {
      state = TransactionState.ROLLED_BACK;
    }

    @Override
    public TransactionContext resetForRetry() {
      return txContext;
    }

    @Override
    public Timestamp getCommitTimestamp() {
      return commitResponse == null ? null : commitResponse.getCommitTimestamp();
    }

    @Override
    public CommitResponse getCommitResponse() {
      return commitResponse;
    }

    @Override
    public TransactionState getState() {
      return state;
    }

    @Override
    public void close() {
      if (state != TransactionState.COMMITTED) {
        state = TransactionState.ROLLED_BACK;
      }
    }
  }

  private static class SimpleResultSet extends ForwardingResultSet {
    private boolean nextCalled = false;
    private boolean onValidRow = false;
    private boolean hasNextReturnedFalse = false;

    SimpleResultSet(ResultSet delegate) {
      super(delegate);
    }

    @Override
    public boolean next() {
      nextCalled = true;
      onValidRow = super.next();
      hasNextReturnedFalse = !onValidRow;
      return onValidRow;
    }

    boolean isNextCalled() {
      return nextCalled;
    }

    @Override
    public ResultSetStats getStats() {
      if (hasNextReturnedFalse) {
        return super.getStats();
      }
      return null;
    }

    @Override
    public long getLong(int columnIndex) {
      if (onValidRow) {
        return super.getLong(columnIndex);
      }
      throw SpannerExceptionFactory.newSpannerException(
          ErrorCode.FAILED_PRECONDITION, "ResultSet is not positioned on a valid row");
    }
  }

  private static ResultSet createSelect1MockResultSet() {
    ResultSet mockResultSet = mock(ResultSet.class);
    when(mockResultSet.next()).thenReturn(true, false);
    when(mockResultSet.getLong(0)).thenReturn(1L);
    when(mockResultSet.getLong("TEST")).thenReturn(1L);
    when(mockResultSet.getColumnType(0)).thenReturn(Type.int64());
    when(mockResultSet.getColumnType("TEST")).thenReturn(Type.int64());
    return mockResultSet;
  }

  private static DdlClient createDefaultMockDdlClient() {
    try {
      DdlClient ddlClient = mock(DdlClient.class);
      @SuppressWarnings("unchecked")
      final OperationFuture<Void, UpdateDatabaseDdlMetadata> operation =
          mock(OperationFuture.class);
      when(operation.get()).thenReturn(null);
      UpdateDatabaseDdlMetadata metadata = UpdateDatabaseDdlMetadata.getDefaultInstance();
      ApiFuture<UpdateDatabaseDdlMetadata> futureMetadata = ApiFutures.immediateFuture(metadata);
      when(operation.getMetadata()).thenReturn(futureMetadata);
      when(ddlClient.executeDdl(anyString())).thenCallRealMethod();
      when(ddlClient.executeDdl(anyList())).thenReturn(operation);
      return ddlClient;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static ConnectionImpl createConnection(final ConnectionOptions options) {
    Spanner spanner = mock(Spanner.class);
    SpannerPool spannerPool = mock(SpannerPool.class);
    when(spannerPool.getSpanner(any(ConnectionOptions.class), any(ConnectionImpl.class)))
        .thenReturn(spanner);
    DdlClient ddlClient = createDefaultMockDdlClient();
    DatabaseClient dbClient = mock(DatabaseClient.class);
    ReadOnlyTransaction singleUseReadOnlyTx = mock(ReadOnlyTransaction.class);

    ResultSet mockResultSetWithStats = createSelect1MockResultSet();
    when(mockResultSetWithStats.getStats()).thenReturn(ResultSetStats.getDefaultInstance());

    final SimpleResultSet select1ResultSet = new SimpleResultSet(createSelect1MockResultSet());
    final SimpleResultSet select1ResultSetWithStats = new SimpleResultSet(mockResultSetWithStats);
    when(singleUseReadOnlyTx.executeQuery(Statement.of(SELECT)))
        .thenAnswer(
            invocation -> {
              if (select1ResultSet.nextCalled) {
                // create a new mock
                return new SimpleResultSet(createSelect1MockResultSet());
              }
              return select1ResultSet;
            });
    when(singleUseReadOnlyTx.analyzeQuery(Statement.of(SELECT), QueryAnalyzeMode.PLAN))
        .thenReturn(select1ResultSetWithStats);
    when(singleUseReadOnlyTx.analyzeQuery(Statement.of(SELECT), QueryAnalyzeMode.PROFILE))
        .thenReturn(select1ResultSetWithStats);
    when(singleUseReadOnlyTx.getReadTimestamp())
        .then(
            invocation -> {
              if (select1ResultSet.isNextCalled() || select1ResultSetWithStats.isNextCalled()) {
                return Timestamp.now();
              }
              throw SpannerExceptionFactory.newSpannerException(
                  ErrorCode.FAILED_PRECONDITION, "No query has returned with any data yet");
            });
    when(dbClient.singleUseReadOnlyTransaction(any(TimestampBound.class)))
        .thenReturn(singleUseReadOnlyTx);

    when(dbClient.transactionManager(any()))
        .thenAnswer(
            invocation -> {
              TransactionContext txContext = mock(TransactionContext.class);
              when(txContext.executeQuery(Statement.of(SELECT)))
                  .thenAnswer(
                      ignored -> {
                        if (select1ResultSet.nextCalled) {
                          // create a new mock
                          return new SimpleResultSet(createSelect1MockResultSet());
                        }
                        return select1ResultSet;
                      });
              when(txContext.analyzeQuery(Statement.of(SELECT), QueryAnalyzeMode.PLAN))
                  .thenReturn(select1ResultSetWithStats);
              when(txContext.analyzeQuery(Statement.of(SELECT), QueryAnalyzeMode.PROFILE))
                  .thenReturn(select1ResultSetWithStats);
              when(txContext.executeUpdate(Statement.of(UPDATE))).thenReturn(1L);
              return new SimpleTransactionManager(txContext, options.isReturnCommitStats());
            });

    when(dbClient.readOnlyTransaction(any(TimestampBound.class)))
        .thenAnswer(
            invocation -> {
              ReadOnlyTransaction tx = mock(ReadOnlyTransaction.class);
              when(tx.executeQuery(Statement.of(SELECT)))
                  .thenAnswer(
                      ignored -> {
                        if (select1ResultSet.nextCalled) {
                          // create a new mock
                          return new SimpleResultSet(createSelect1MockResultSet());
                        }
                        return select1ResultSet;
                      });
              when(tx.analyzeQuery(Statement.of(SELECT), QueryAnalyzeMode.PLAN))
                  .thenReturn(select1ResultSetWithStats);
              when(tx.analyzeQuery(Statement.of(SELECT), QueryAnalyzeMode.PROFILE))
                  .thenReturn(select1ResultSetWithStats);
              when(tx.getReadTimestamp())
                  .then(
                      ignored -> {
                        if (select1ResultSet.isNextCalled()
                            || select1ResultSetWithStats.isNextCalled()) {
                          return Timestamp.now();
                        }
                        throw SpannerExceptionFactory.newSpannerException(
                            ErrorCode.FAILED_PRECONDITION,
                            "No query has returned with any data yet");
                      });
              return tx;
            });

    when(dbClient.readWriteTransaction())
        .thenAnswer(
            new Answer<TransactionRunner>() {
              @Override
              public TransactionRunner answer(InvocationOnMock invocation) {
                return new TransactionRunner() {
                  private CommitResponse commitResponse;

                  @Override
                  public <T> T run(TransactionCallable<T> callable) {
                    commitResponse = new CommitResponse(Timestamp.ofTimeSecondsAndNanos(1, 1));
                    TransactionContext transaction = mock(TransactionContext.class);
                    when(transaction.executeUpdate(Statement.of(UPDATE))).thenReturn(1L);
                    try {
                      return callable.run(transaction);
                    } catch (Exception e) {
                      throw SpannerExceptionFactory.newSpannerException(e);
                    }
                  }

                  @Override
                  public Timestamp getCommitTimestamp() {
                    return commitResponse == null ? null : commitResponse.getCommitTimestamp();
                  }

                  @Override
                  public CommitResponse getCommitResponse() {
                    return commitResponse;
                  }

                  @Override
                  public TransactionRunner allowNestedTransaction() {
                    return this;
                  }
                };
              }
            });
    return new ConnectionImpl(options, spannerPool, ddlClient, dbClient);
  }

  @Test
  public void testExecuteSetAutocommitOn() {
    try (ConnectionImpl subject =
        createConnection(
            ConnectionOptions.newBuilder()
                .setCredentials(NoCredentials.getInstance())
                .setUri(URI + ";autocommit=false")
                .build())) {
      assertThat(subject.isAutocommit(), is(false));

      StatementResult res = subject.execute(Statement.of("set autocommit = true"));
      assertThat(res.getResultType(), is(equalTo(ResultType.NO_RESULT)));
      assertThat(subject.isAutocommit(), is(true));
    }
  }

  @Test
  public void testExecuteSetAutocommitOff() {
    try (ConnectionImpl subject =
        createConnection(
            ConnectionOptions.newBuilder()
                .setCredentials(NoCredentials.getInstance())
                .setUri(URI)
                .build())) {
      assertThat(subject.isAutocommit(), is(true));

      StatementResult res = subject.execute(Statement.of("set autocommit = false"));
      assertThat(res.getResultType(), is(equalTo(ResultType.NO_RESULT)));
      assertThat(subject.isAutocommit(), is(false));
    }
  }

  @Test
  public void testExecuteGetAutocommit() {
    try (ConnectionImpl subject =
        createConnection(
            ConnectionOptions.newBuilder()
                .setCredentials(NoCredentials.getInstance())
                .setUri(URI)
                .build())) {

      // assert that autocommit is true (default)
      assertThat(subject.isAutocommit(), is(true));
      StatementResult res = subject.execute(Statement.of("show variable autocommit"));
      assertThat(res.getResultType(), is(equalTo(ResultType.RESULT_SET)));
      assertThat(res.getResultSet().next(), is(true));
      assertThat(res.getResultSet().getBoolean("AUTOCOMMIT"), is(true));

      // set autocommit to false and assert that autocommit is false
      subject.execute(Statement.of("set autocommit = false"));
      assertThat(subject.isAutocommit(), is(false));
      res = subject.execute(Statement.of("show variable autocommit"));
      assertThat(res.getResultType(), is(equalTo(ResultType.RESULT_SET)));
      assertThat(res.getResultSet().next(), is(true));
      assertThat(res.getResultSet().getBoolean("AUTOCOMMIT"), is(false));
    }
  }

  @Test
  public void testExecuteSetReadOnlyOn() {
    try (ConnectionImpl subject =
        createConnection(
            ConnectionOptions.newBuilder()
                .setCredentials(NoCredentials.getInstance())
                .setUri(URI)
                .build())) {
      assertThat(subject.isReadOnly(), is(false));

      StatementResult res = subject.execute(Statement.of("set readonly = true"));
      assertThat(res.getResultType(), is(equalTo(ResultType.NO_RESULT)));
      assertThat(subject.isReadOnly(), is(true));
    }
  }

  @Test
  public void testExecuteSetReadOnlyOff() {
    try (ConnectionImpl subject =
        createConnection(
            ConnectionOptions.newBuilder()
                .setCredentials(NoCredentials.getInstance())
                .setUri(URI + ";readonly=true")
                .build())) {
      assertThat(subject.isReadOnly(), is(true));

      StatementResult res = subject.execute(Statement.of("set readonly = false"));
      assertThat(res.getResultType(), is(equalTo(ResultType.NO_RESULT)));
      assertThat(subject.isReadOnly(), is(false));
    }
  }

  @Test
  public void testExecuteGetReadOnly() {
    try (ConnectionImpl subject =
        createConnection(
            ConnectionOptions.newBuilder()
                .setCredentials(NoCredentials.getInstance())
                .setUri(URI)
                .build())) {

      // assert that read only is false (default)
      assertThat(subject.isReadOnly(), is(false));
      StatementResult res = subject.execute(Statement.of("show variable readonly"));
      assertThat(res.getResultType(), is(equalTo(ResultType.RESULT_SET)));
      assertThat(res.getResultSet().next(), is(true));
      assertThat(res.getResultSet().getBoolean("READONLY"), is(false));

      // set read only to true and assert that read only is true
      subject.execute(Statement.of("set readonly = true"));
      assertThat(subject.isReadOnly(), is(true));
      res = subject.execute(Statement.of("show variable readonly"));
      assertThat(res.getResultType(), is(equalTo(ResultType.RESULT_SET)));
      assertThat(res.getResultSet().next(), is(true));
      assertThat(res.getResultSet().getBoolean("READONLY"), is(true));
    }
  }

  @Test
  public void testExecuteSetAutocommitDmlMode() {
    try (ConnectionImpl subject =
        createConnection(
            ConnectionOptions.newBuilder()
                .setCredentials(NoCredentials.getInstance())
                .setUri(URI)
                .build())) {
      assertThat(subject.isAutocommit(), is(true));
      assertThat(subject.getAutocommitDmlMode(), is(equalTo(AutocommitDmlMode.TRANSACTIONAL)));

      StatementResult res =
          subject.execute(Statement.of("set autocommit_dml_mode='PARTITIONED_NON_ATOMIC'"));
      assertThat(res.getResultType(), is(equalTo(ResultType.NO_RESULT)));
      assertThat(
          subject.getAutocommitDmlMode(), is(equalTo(AutocommitDmlMode.PARTITIONED_NON_ATOMIC)));

      res = subject.execute(Statement.of("set autocommit_dml_mode='TRANSACTIONAL'"));
      assertThat(res.getResultType(), is(equalTo(ResultType.NO_RESULT)));
      assertThat(subject.getAutocommitDmlMode(), is(equalTo(AutocommitDmlMode.TRANSACTIONAL)));
    }
  }

  @Test
  public void testExecuteSetAutocommitDmlModeInvalidValue() {
    try (ConnectionImpl subject =
        createConnection(
            ConnectionOptions.newBuilder()
                .setCredentials(NoCredentials.getInstance())
                .setUri(URI)
                .build())) {
      assertThat(subject.isAutocommit(), is(true));
      assertThat(subject.getAutocommitDmlMode(), is(equalTo(AutocommitDmlMode.TRANSACTIONAL)));

      ErrorCode expected = null;
      try {
        subject.execute(Statement.of("set autocommit_dml_mode='NON_EXISTENT_VALUE'"));
      } catch (SpannerException e) {
        expected = e.getErrorCode();
      }
      assertThat(expected, is(equalTo(ErrorCode.INVALID_ARGUMENT)));
    }
  }

  @Test
  public void testExecuteGetAutocommitDmlMode() {
    try (ConnectionImpl subject =
        createConnection(
            ConnectionOptions.newBuilder()
                .setCredentials(NoCredentials.getInstance())
                .setUri(URI)
                .build())) {
      assertThat(subject.isAutocommit(), is(true));
      assertThat(subject.getAutocommitDmlMode(), is(equalTo(AutocommitDmlMode.TRANSACTIONAL)));

      StatementResult res = subject.execute(Statement.of("show variable autocommit_dml_mode"));
      assertThat(res.getResultType(), is(equalTo(ResultType.RESULT_SET)));
      assertThat(res.getResultSet().next(), is(true));
      assertThat(
          res.getResultSet().getString("AUTOCOMMIT_DML_MODE"),
          is(equalTo(AutocommitDmlMode.TRANSACTIONAL.toString())));

      subject.execute(Statement.of("set autocommit_dml_mode='PARTITIONED_NON_ATOMIC'"));
      res = subject.execute(Statement.of("show variable autocommit_dml_mode"));
      assertThat(res.getResultType(), is(equalTo(ResultType.RESULT_SET)));
      assertThat(res.getResultSet().next(), is(true));
      assertThat(
          res.getResultSet().getString("AUTOCOMMIT_DML_MODE"),
          is(equalTo(AutocommitDmlMode.PARTITIONED_NON_ATOMIC.toString())));
    }
  }

  @Test
  public void testExecuteSetOptimizerVersion() {
    try (ConnectionImpl subject =
        createConnection(
            ConnectionOptions.newBuilder()
                .setCredentials(NoCredentials.getInstance())
                .setUri(URI)
                .build())) {
      assertThat(subject.getOptimizerVersion(), is(equalTo("")));

      StatementResult res = subject.execute(Statement.of("set optimizer_version='1'"));
      assertThat(res.getResultType(), is(equalTo(ResultType.NO_RESULT)));
      assertThat(subject.getOptimizerVersion(), is(equalTo("1")));

      res = subject.execute(Statement.of("set optimizer_version='1000'"));
      assertThat(res.getResultType(), is(equalTo(ResultType.NO_RESULT)));
      assertThat(subject.getOptimizerVersion(), is(equalTo("1000")));

      res = subject.execute(Statement.of("set optimizer_version='latest'"));
      assertThat(res.getResultType(), is(equalTo(ResultType.NO_RESULT)));
      assertThat(subject.getOptimizerVersion(), is(equalTo("latest")));

      res = subject.execute(Statement.of("set optimizer_version=''"));
      assertThat(res.getResultType(), is(equalTo(ResultType.NO_RESULT)));
      assertThat(subject.getOptimizerVersion(), is(equalTo("")));
    }
  }

  @Test
  public void testExecuteSetOptimizerVersionInvalidValue() {
    try (ConnectionImpl subject =
        createConnection(
            ConnectionOptions.newBuilder()
                .setCredentials(NoCredentials.getInstance())
                .setUri(URI)
                .build())) {
      assertThat(subject.getOptimizerVersion(), is(equalTo("")));

      try {
        subject.execute(Statement.of("set optimizer_version='NOT_A_VERSION'"));
        fail("Missing expected exception");
      } catch (SpannerException e) {
        assertThat(e.getErrorCode(), is(equalTo(ErrorCode.INVALID_ARGUMENT)));
      }
    }
  }

  @Test
  public void testExecuteGetOptimizerVersion() {
    try (ConnectionImpl subject =
        createConnection(
            ConnectionOptions.newBuilder()
                .setCredentials(NoCredentials.getInstance())
                .setUri(URI)
                .build())) {
      assertThat(subject.getOptimizerVersion(), is(equalTo("")));

      StatementResult res = subject.execute(Statement.of("show variable optimizer_version"));
      assertThat(res.getResultType(), is(equalTo(ResultType.RESULT_SET)));
      assertThat(res.getResultSet().next(), is(true));
      assertThat(res.getResultSet().getString("OPTIMIZER_VERSION"), is(equalTo("")));

      subject.execute(Statement.of("set optimizer_version='1'"));
      res = subject.execute(Statement.of("show variable optimizer_version"));
      assertThat(res.getResultType(), is(equalTo(ResultType.RESULT_SET)));
      assertThat(res.getResultSet().next(), is(true));
      assertThat(res.getResultSet().getString("OPTIMIZER_VERSION"), is(equalTo("1")));
    }
  }

  @Test
  public void testExecuteSetOptimizerStatisticsPackage() {
    try (ConnectionImpl subject =
        createConnection(
            ConnectionOptions.newBuilder()
                .setCredentials(NoCredentials.getInstance())
                .setUri(URI)
                .build())) {
      assertThat(subject.getOptimizerStatisticsPackage(), is(equalTo("")));

      StatementResult res =
          subject.execute(Statement.of("set optimizer_statistics_package='custom-package'"));
      assertThat(res.getResultType(), is(equalTo(ResultType.NO_RESULT)));
      assertThat(subject.getOptimizerStatisticsPackage(), is(equalTo("custom-package")));

      res = subject.execute(Statement.of("set optimizer_statistics_package=''"));
      assertThat(res.getResultType(), is(equalTo(ResultType.NO_RESULT)));
      assertThat(subject.getOptimizerStatisticsPackage(), is(equalTo("")));
    }
  }

  @Test
  public void testExecuteSetOptimizerStatisticsPackageInvalidValue() {
    try (ConnectionImpl subject =
        createConnection(
            ConnectionOptions.newBuilder()
                .setCredentials(NoCredentials.getInstance())
                .setUri(URI)
                .build())) {
      assertThat(subject.getOptimizerVersion(), is(equalTo("")));

      try {
        subject.execute(Statement.of("set optimizer_statistics_package='   '"));
        fail("Missing expected exception");
      } catch (SpannerException e) {
        assertThat(e.getErrorCode(), is(equalTo(ErrorCode.INVALID_ARGUMENT)));
      }
    }
  }

  @Test
  public void testExecuteGetOptimizerStatisticsPackage() {
    try (ConnectionImpl subject =
        createConnection(
            ConnectionOptions.newBuilder()
                .setCredentials(NoCredentials.getInstance())
                .setUri(URI)
                .build())) {
      assertThat(subject.getOptimizerStatisticsPackage(), is(equalTo("")));

      StatementResult res =
          subject.execute(Statement.of("show variable optimizer_statistics_package"));
      assertThat(res.getResultType(), is(equalTo(ResultType.RESULT_SET)));
      assertThat(res.getResultSet().next(), is(true));
      assertThat(res.getResultSet().getString("OPTIMIZER_STATISTICS_PACKAGE"), is(equalTo("")));

      subject.execute(Statement.of("set optimizer_statistics_package='custom-package'"));
      res = subject.execute(Statement.of("show variable optimizer_statistics_package"));
      assertThat(res.getResultType(), is(equalTo(ResultType.RESULT_SET)));
      assertThat(res.getResultSet().next(), is(true));
      assertThat(
          res.getResultSet().getString("OPTIMIZER_STATISTICS_PACKAGE"),
          is(equalTo("custom-package")));
    }
  }

  @Test
  public void testExecuteSetReturnCommitStats() {
    try (ConnectionImpl subject =
        createConnection(
            ConnectionOptions.newBuilder()
                .setCredentials(NoCredentials.getInstance())
                .setUri(URI)
                .build())) {
      assertFalse(subject.isReturnCommitStats());

      StatementResult result = subject.execute(Statement.of("set return_commit_stats=true"));
      assertEquals(ResultType.NO_RESULT, result.getResultType());
      assertTrue(subject.isReturnCommitStats());

      result = subject.execute(Statement.of("set return_commit_stats=false"));
      assertEquals(ResultType.NO_RESULT, result.getResultType());
      assertFalse(subject.isReturnCommitStats());
    }
  }

  @Test
  public void testExecuteSetReturnCommitStatsInvalidValue() {
    try (ConnectionImpl subject =
        createConnection(
            ConnectionOptions.newBuilder()
                .setCredentials(NoCredentials.getInstance())
                .setUri(URI)
                .build())) {
      assertFalse(subject.isReturnCommitStats());

      try {
        subject.execute(Statement.of("set return_commit_stats=yes"));
        fail("Missing expected exception");
      } catch (SpannerException e) {
        assertEquals(ErrorCode.INVALID_ARGUMENT, e.getErrorCode());
      }
    }
  }

  @Test
  public void testExecuteGetReturnCommitStats() {
    try (ConnectionImpl subject =
        createConnection(
            ConnectionOptions.newBuilder()
                .setCredentials(NoCredentials.getInstance())
                .setUri(URI)
                .build())) {
      assertFalse(subject.isReturnCommitStats());

      StatementResult returnCommitStatsFalse =
          subject.execute(Statement.of("show variable return_commit_stats"));
      assertEquals(ResultType.RESULT_SET, returnCommitStatsFalse.getResultType());
      assertTrue(returnCommitStatsFalse.getResultSet().next());
      assertFalse(returnCommitStatsFalse.getResultSet().getBoolean("RETURN_COMMIT_STATS"));

      subject.execute(Statement.of("set return_commit_stats=true"));
      StatementResult returnCommitStatsTrue =
          subject.execute(Statement.of("show variable return_commit_stats"));
      assertEquals(ResultType.RESULT_SET, returnCommitStatsTrue.getResultType());
      assertTrue(returnCommitStatsTrue.getResultSet().next());
      assertTrue(returnCommitStatsTrue.getResultSet().getBoolean("RETURN_COMMIT_STATS"));
    }
  }

  @Test
  public void testExecuteSetStatementTimeout() {
    try (ConnectionImpl subject =
        createConnection(
            ConnectionOptions.newBuilder()
                .setCredentials(NoCredentials.getInstance())
                .setUri(URI)
                .build())) {
      assertThat(subject.getStatementTimeout(TimeUnit.MILLISECONDS), is(equalTo(0L)));

      for (TimeUnit unit : ReadOnlyStalenessUtil.SUPPORTED_UNITS) {
        for (Long timeout : new Long[] {1L, 100L, 10000L, 315576000000L}) {
          StatementResult res =
              subject.execute(
                  Statement.of(
                      String.format(
                          "set statement_timeout='%d%s'",
                          timeout, ReadOnlyStalenessUtil.getTimeUnitAbbreviation(unit))));
          assertThat(res.getResultType(), is(equalTo(ResultType.NO_RESULT)));
          assertThat(subject.getStatementTimeout(unit), is(equalTo(timeout)));
          assertThat(subject.hasStatementTimeout(), is(true));

          StatementResult resNoTimeout =
              subject.execute(Statement.of("set statement_timeout=null"));
          assertThat(resNoTimeout.getResultType(), is(equalTo(ResultType.NO_RESULT)));
          assertThat(subject.getStatementTimeout(unit), is(equalTo(0L)));
          assertThat(subject.hasStatementTimeout(), is(false));
        }
      }
    }
  }

  @Test
  public void testExecuteSetStatementTimeoutInvalidValue() {
    try (ConnectionImpl subject =
        createConnection(
            ConnectionOptions.newBuilder()
                .setCredentials(NoCredentials.getInstance())
                .setUri(URI)
                .build())) {
      assertThat(subject.getStatementTimeout(TimeUnit.MILLISECONDS), is(equalTo(0L)));

      ErrorCode expected = null;
      try {
        subject.execute(Statement.of("set statement_timeout=-1"));
      } catch (SpannerException e) {
        expected = e.getErrorCode();
      }
      assertThat(expected, is(equalTo(ErrorCode.INVALID_ARGUMENT)));
    }
  }

  @Test
  public void testExecuteGetStatementTimeout() {
    try (ConnectionImpl subject =
        createConnection(
            ConnectionOptions.newBuilder()
                .setCredentials(NoCredentials.getInstance())
                .setUri(URI)
                .build())) {
      assertThat(subject.getStatementTimeout(TimeUnit.MILLISECONDS), is(equalTo(0L)));

      for (TimeUnit unit : ReadOnlyStalenessUtil.SUPPORTED_UNITS) {
        for (Long timeout : new Long[] {1L, 100L, 10000L, 315576000000L}) {
          subject.execute(
              Statement.of(
                  String.format(
                      "set statement_timeout='%d%s'",
                      timeout, ReadOnlyStalenessUtil.getTimeUnitAbbreviation(unit))));
          StatementResult res = subject.execute(Statement.of("show variable statement_timeout"));
          assertThat(res.getResultType(), is(equalTo(ResultType.RESULT_SET)));
          assertThat(res.getResultSet().next(), is(true));
          TimeUnit appropriateUnit =
              ReadOnlyStalenessUtil.getAppropriateTimeUnit(new StatementTimeoutGetter(subject));
          assertThat(
              res.getResultSet().getString("STATEMENT_TIMEOUT"),
              is(
                  equalTo(
                      subject.getStatementTimeout(appropriateUnit)
                          + ReadOnlyStalenessUtil.getTimeUnitAbbreviation(appropriateUnit))));

          subject.execute(Statement.of("set statement_timeout=null"));
          StatementResult resNoTimeout =
              subject.execute(Statement.of("show variable statement_timeout"));
          assertThat(resNoTimeout.getResultType(), is(equalTo(ResultType.RESULT_SET)));
          assertThat(resNoTimeout.getResultSet().next(), is(true));
          assertThat(resNoTimeout.getResultSet().isNull("STATEMENT_TIMEOUT"), is(true));
        }
      }
    }
  }

  @Test
  public void testExecuteGetReadTimestamp() {
    try (ConnectionImpl subject =
        createConnection(
            ConnectionOptions.newBuilder()
                .setCredentials(NoCredentials.getInstance())
                .setUri(URI)
                .build())) {
      subject.beginTransaction();
      subject.setTransactionMode(TransactionMode.READ_ONLY_TRANSACTION);
      subject.executeQuery(Statement.of(AbstractConnectionImplTest.SELECT));
      StatementResult res = subject.execute(Statement.of("show variable read_timestamp"));
      assertThat(res.getResultType(), is(equalTo(ResultType.RESULT_SET)));
      assertThat(res.getResultSet().next(), is(true));
      assertThat(res.getResultSet().getTimestamp("READ_TIMESTAMP"), is(notNullValue()));
      subject.commit();
    }
  }

  @Test
  public void testExecuteGetCommitTimestamp() {
    try (ConnectionImpl subject =
        createConnection(
            ConnectionOptions.newBuilder()
                .setCredentials(NoCredentials.getInstance())
                .setUri(URI)
                .build())) {
      subject.beginTransaction();
      subject.executeQuery(Statement.of(AbstractConnectionImplTest.SELECT)).next();
      subject.commit();
      StatementResult res = subject.execute(Statement.of("show variable commit_timestamp"));
      assertThat(res.getResultType(), is(equalTo(ResultType.RESULT_SET)));
      assertThat(res.getResultSet().next(), is(true));
      assertThat(res.getResultSet().getTimestamp("COMMIT_TIMESTAMP"), is(notNullValue()));
    }
  }

  @Test
  public void testExecuteGetCommitResponse() {
    try (ConnectionImpl subject =
        createConnection(
            ConnectionOptions.newBuilder()
                .setCredentials(NoCredentials.getInstance())
                .setUri(URI)
                .build())) {
      subject.beginTransaction();
      subject.executeQuery(Statement.of(AbstractConnectionImplTest.SELECT)).next();
      subject.commit();
      StatementResult response = subject.execute(Statement.of("show variable commit_response"));
      assertEquals(ResultType.RESULT_SET, response.getResultType());
      assertTrue(response.getResultSet().next());
      assertNotNull(response.getResultSet().getTimestamp("COMMIT_TIMESTAMP"));
      assertTrue(response.getResultSet().isNull("MUTATION_COUNT"));
      assertFalse(response.getResultSet().next());
    }

    try (ConnectionImpl subject =
        createConnection(
            ConnectionOptions.newBuilder()
                .setCredentials(NoCredentials.getInstance())
                .setUri(URI + ";returnCommitStats=true")
                .build())) {
      subject.beginTransaction();
      subject.executeQuery(Statement.of(AbstractConnectionImplTest.SELECT)).next();
      subject.commit();
      StatementResult response = subject.execute(Statement.of("show variable commit_response"));
      assertEquals(ResultType.RESULT_SET, response.getResultType());
      assertTrue(response.getResultSet().next());
      assertNotNull(response.getResultSet().getTimestamp("COMMIT_TIMESTAMP"));
      assertFalse(response.getResultSet().isNull("MUTATION_COUNT"));
      assertFalse(response.getResultSet().next());
    }
  }

  private static final class StalenessDuration {
    private final long duration;
    private final TimeUnit unit;

    private StalenessDuration(long duration, TimeUnit unit) {
      this.duration = duration;
      this.unit = unit;
    }

    @Override
    public String toString() {
      GetExactStaleness getExactStalenessFunction =
          new GetExactStaleness(TimestampBound.ofExactStaleness(duration, unit));
      return ReadOnlyStalenessUtil.durationToString(getExactStalenessFunction);
    }
  }

  @Test
  public void testExecuteGetReadOnlyStaleness() {
    Map<TimestampBound.Mode, Timestamp> timestamps = new HashMap<>();
    timestamps.put(Mode.READ_TIMESTAMP, ReadOnlyStalenessUtil.parseRfc3339("2018-10-08T14:05:10Z"));
    timestamps.put(
        Mode.MIN_READ_TIMESTAMP, ReadOnlyStalenessUtil.parseRfc3339("2018-10-08T14:05:10.12345Z"));
    Map<TimestampBound.Mode, StalenessDuration> durations = new HashMap<>();
    durations.put(Mode.EXACT_STALENESS, new StalenessDuration(1000L, TimeUnit.MILLISECONDS));
    durations.put(Mode.MAX_STALENESS, new StalenessDuration(1234567L, TimeUnit.MICROSECONDS));
    List<TimestampBound> stalenesses =
        Arrays.asList(
            TimestampBound.strong(),
            TimestampBound.ofReadTimestamp(timestamps.get(Mode.READ_TIMESTAMP)),
            TimestampBound.ofMinReadTimestamp(timestamps.get(Mode.MIN_READ_TIMESTAMP)),
            TimestampBound.ofExactStaleness(
                durations.get(Mode.EXACT_STALENESS).duration,
                durations.get(Mode.EXACT_STALENESS).unit),
            TimestampBound.ofMaxStaleness(
                durations.get(Mode.MAX_STALENESS).duration,
                durations.get(Mode.MAX_STALENESS).unit));
    try (ConnectionImpl subject =
        createConnection(
            ConnectionOptions.newBuilder()
                .setCredentials(NoCredentials.getInstance())
                .setUri(URI)
                .build())) {
      for (TimestampBound staleness : stalenesses) {
        subject.setReadOnlyStaleness(staleness);
        StatementResult res = subject.execute(Statement.of("show variable read_only_staleness"));
        assertThat(res.getResultType(), is(equalTo(ResultType.RESULT_SET)));
        assertThat(res.getResultSet().next(), is(true));
        assertThat(
            res.getResultSet().getString("READ_ONLY_STALENESS"),
            is(equalTo(ReadOnlyStalenessUtil.timestampBoundToString(staleness))));
      }
    }
  }

  @Test
  public void testExecuteSetReadOnlyStaleness() {
    Map<TimestampBound.Mode, Timestamp> timestamps = new HashMap<>();
    timestamps.put(Mode.READ_TIMESTAMP, ReadOnlyStalenessUtil.parseRfc3339("2018-10-08T12:13:14Z"));
    timestamps.put(
        Mode.MIN_READ_TIMESTAMP,
        ReadOnlyStalenessUtil.parseRfc3339("2018-10-08T14:13:14.1234+02:00"));
    Map<TimestampBound.Mode, StalenessDuration> durations = new HashMap<>();
    durations.put(Mode.EXACT_STALENESS, new StalenessDuration(1000L, TimeUnit.MILLISECONDS));
    durations.put(Mode.MAX_STALENESS, new StalenessDuration(1234567L, TimeUnit.MICROSECONDS));
    List<TimestampBound> stalenesses =
        Arrays.asList(
            TimestampBound.strong(),
            TimestampBound.ofReadTimestamp(timestamps.get(Mode.READ_TIMESTAMP)),
            TimestampBound.ofMinReadTimestamp(timestamps.get(Mode.MIN_READ_TIMESTAMP)),
            TimestampBound.ofExactStaleness(
                durations.get(Mode.EXACT_STALENESS).duration,
                durations.get(Mode.EXACT_STALENESS).unit),
            TimestampBound.ofMaxStaleness(
                durations.get(Mode.MAX_STALENESS).duration,
                durations.get(Mode.MAX_STALENESS).unit));
    try (ConnectionImpl subject =
        createConnection(
            ConnectionOptions.newBuilder()
                .setCredentials(NoCredentials.getInstance())
                .setUri(URI)
                .build())) {
      for (TimestampBound staleness : stalenesses) {
        StatementResult res =
            subject.execute(
                Statement.of(
                    String.format(
                        "set read_only_staleness='%s'",
                        ReadOnlyStalenessUtil.timestampBoundToString(staleness))));
        assertThat(res.getResultType(), is(equalTo(ResultType.NO_RESULT)));
        assertThat(subject.getReadOnlyStaleness(), is(equalTo(staleness)));
      }
    }
  }

  @Test
  public void testExecuteBeginTransaction() {
    try (ConnectionImpl subject =
        createConnection(
            ConnectionOptions.newBuilder()
                .setCredentials(NoCredentials.getInstance())
                .setUri(URI)
                .build())) {
      assertThat(subject.isInTransaction(), is(false));

      StatementResult res = subject.execute(Statement.of("begin transaction"));
      assertThat(res.getResultType(), is(equalTo(ResultType.NO_RESULT)));
      assertThat(subject.isInTransaction(), is(true));
    }
  }

  @Test
  public void testExecuteCommitTransaction() {
    try (ConnectionImpl subject =
        createConnection(
            ConnectionOptions.newBuilder()
                .setCredentials(NoCredentials.getInstance())
                .setUri(URI)
                .build())) {
      subject.execute(Statement.of("begin transaction"));
      assertThat(subject.isInTransaction(), is(true));

      StatementResult res = subject.execute(Statement.of("commit"));
      assertThat(res.getResultType(), is(equalTo(ResultType.NO_RESULT)));
      assertThat(subject.isInTransaction(), is(false));
    }
  }

  @Test
  public void testExecuteRollbackTransaction() {
    try (ConnectionImpl subject =
        createConnection(
            ConnectionOptions.newBuilder()
                .setCredentials(NoCredentials.getInstance())
                .setUri(URI)
                .build())) {
      subject.execute(Statement.of("begin"));
      assertThat(subject.isInTransaction(), is(true));

      StatementResult res = subject.execute(Statement.of("rollback"));
      assertThat(res.getResultType(), is(equalTo(ResultType.NO_RESULT)));
      assertThat(subject.isInTransaction(), is(false));
    }
  }

  @Test
  public void testExecuteSetTransactionReadOnly() {
    try (ConnectionImpl subject =
        createConnection(
            ConnectionOptions.newBuilder()
                .setCredentials(NoCredentials.getInstance())
                .setUri(URI)
                .build())) {
      subject.execute(Statement.of("begin"));
      assertThat(subject.getTransactionMode(), is(equalTo(TransactionMode.READ_WRITE_TRANSACTION)));
      assertThat(subject.isInTransaction(), is(true));

      StatementResult res = subject.execute(Statement.of("set transaction read only"));
      assertThat(res.getResultType(), is(equalTo(ResultType.NO_RESULT)));
      assertThat(subject.getTransactionMode(), is(equalTo(TransactionMode.READ_ONLY_TRANSACTION)));
    }
  }

  @Test
  public void testExecuteSetTransactionReadWrite() {
    try (ConnectionImpl subject =
        createConnection(
            ConnectionOptions.newBuilder()
                .setCredentials(NoCredentials.getInstance())
                .setUri(URI + ";readonly=true")
                .build())) {
      subject.execute(Statement.of("begin"));
      assertThat(subject.getTransactionMode(), is(equalTo(TransactionMode.READ_ONLY_TRANSACTION)));
      assertThat(subject.isInTransaction(), is(true));

      // end the current temporary transaction and turn off read-only mode
      subject.execute(Statement.of("commit"));
      subject.execute(Statement.of("set readonly = false"));

      subject.execute(Statement.of("begin"));
      StatementResult res = subject.execute(Statement.of("set transaction read only"));
      assertThat(res.getResultType(), is(equalTo(ResultType.NO_RESULT)));
      assertThat(subject.getTransactionMode(), is(equalTo(TransactionMode.READ_ONLY_TRANSACTION)));
      res = subject.execute(Statement.of("set transaction read write"));
      assertThat(res.getResultType(), is(equalTo(ResultType.NO_RESULT)));
      assertThat(subject.getTransactionMode(), is(equalTo(TransactionMode.READ_WRITE_TRANSACTION)));
    }
  }

  @Test
  public void testExecuteStartDdlBatch() {
    try (ConnectionImpl subject =
        createConnection(
            ConnectionOptions.newBuilder()
                .setCredentials(NoCredentials.getInstance())
                .setUri(URI)
                .build())) {
      StatementResult res = subject.execute(Statement.of("start batch ddl"));
      assertThat(res.getResultType(), is(equalTo(ResultType.NO_RESULT)));
      assertThat(subject.getUnitOfWorkType(), is(equalTo(UnitOfWorkType.DDL_BATCH)));
      assertThat(subject.isInTransaction(), is(false));
    }
  }

  @Test
  public void testDefaultIsAutocommit() {
    try (ConnectionImpl subject =
        createConnection(
            ConnectionOptions.newBuilder()
                .setCredentials(NoCredentials.getInstance())
                .setUri(URI)
                .build())) {
      assertThat(subject.isAutocommit(), is(true));
      assertThat(subject.isInTransaction(), is(false));
    }
  }

  @Test
  public void testDefaultIsReadWrite() {
    try (ConnectionImpl subject =
        createConnection(
            ConnectionOptions.newBuilder()
                .setCredentials(NoCredentials.getInstance())
                .setUri(URI)
                .build())) {
      assertThat(subject.isReadOnly(), is(false));
    }
  }

  @Test
  public void testDefaultTransactionIsReadWrite() {
    try (ConnectionImpl subject =
        createConnection(
            ConnectionOptions.newBuilder()
                .setCredentials(NoCredentials.getInstance())
                .setUri(URI)
                .build())) {
      for (boolean autocommit : new Boolean[] {true, false}) {
        subject.setAutocommit(autocommit);
        subject.execute(Statement.of("begin"));
        assertThat(
            subject.getTransactionMode(), is(equalTo(TransactionMode.READ_WRITE_TRANSACTION)));
        subject.commit();

        subject.execute(Statement.of("begin"));
        subject.execute(Statement.of("set transaction read only"));
        assertThat(
            subject.getTransactionMode(), is(equalTo(TransactionMode.READ_ONLY_TRANSACTION)));
        subject.commit();

        subject.execute(Statement.of("begin"));
        assertThat(
            subject.getTransactionMode(), is(equalTo(TransactionMode.READ_WRITE_TRANSACTION)));
        subject.commit();

        subject.execute(Statement.of("start batch ddl"));
        assertThat(subject.getUnitOfWorkType(), is(equalTo(UnitOfWorkType.DDL_BATCH)));
        subject.runBatch();

        subject.execute(Statement.of("begin"));
        assertThat(
            subject.getTransactionMode(), is(equalTo(TransactionMode.READ_WRITE_TRANSACTION)));
        subject.commit();
      }
    }
  }

  @Test
  public void testDefaultTransactionIsReadOnly() {
    try (ConnectionImpl subject =
        createConnection(
            ConnectionOptions.newBuilder()
                .setCredentials(NoCredentials.getInstance())
                .setUri(URI + ";readOnly=true")
                .build())) {
      for (boolean autocommit : new Boolean[] {true, false}) {
        subject.setAutocommit(autocommit);
        subject.execute(Statement.of("begin"));
        assertThat(
            subject.getTransactionMode(), is(equalTo(TransactionMode.READ_ONLY_TRANSACTION)));
        subject.commit();
      }
    }
  }

  /**
   * ReadOnlyStaleness is a session setting for a connection. However, certain settings are only
   * allowed when the connection is in autocommit mode. The setting therefore must be reset to its
   * default {@link TimestampBound#strong()} when the current setting is not compatible with
   * transactional mode.
   */
  @Test
  public void testResetReadOnlyStaleness() {
    try (ConnectionImpl subject =
        createConnection(
            ConnectionOptions.newBuilder()
                .setCredentials(NoCredentials.getInstance())
                .setUri(URI)
                .build())) {
      assertThat(subject.isAutocommit(), is(true));
      assertThat(subject.getReadOnlyStaleness().getMode(), is(equalTo(TimestampBound.Mode.STRONG)));

      // the following values are always allowed
      subject.setReadOnlyStaleness(TimestampBound.strong());
      assertThat(subject.getReadOnlyStaleness().getMode(), is(equalTo(TimestampBound.Mode.STRONG)));
      subject.setAutocommit(false);
      assertThat(subject.getReadOnlyStaleness().getMode(), is(equalTo(TimestampBound.Mode.STRONG)));
      subject.setAutocommit(true);
      assertThat(subject.getReadOnlyStaleness().getMode(), is(equalTo(TimestampBound.Mode.STRONG)));

      subject.setReadOnlyStaleness(TimestampBound.ofReadTimestamp(Timestamp.MAX_VALUE));
      subject.setAutocommit(false);
      assertThat(
          subject.getReadOnlyStaleness(),
          is(equalTo(TimestampBound.ofReadTimestamp(Timestamp.MAX_VALUE))));
      subject.setAutocommit(true);
      assertThat(
          subject.getReadOnlyStaleness(),
          is(equalTo(TimestampBound.ofReadTimestamp(Timestamp.MAX_VALUE))));

      subject.setReadOnlyStaleness(TimestampBound.ofExactStaleness(10L, TimeUnit.SECONDS));
      subject.setAutocommit(false);
      assertThat(
          subject.getReadOnlyStaleness(),
          is(equalTo(TimestampBound.ofExactStaleness(10L, TimeUnit.SECONDS))));
      subject.setAutocommit(true);
      assertThat(
          subject.getReadOnlyStaleness(),
          is(equalTo(TimestampBound.ofExactStaleness(10L, TimeUnit.SECONDS))));

      // the following values are only allowed in autocommit mode. Turning off autocommit will
      // return the setting to its default
      subject.setReadOnlyStaleness(TimestampBound.ofMinReadTimestamp(Timestamp.MAX_VALUE));
      assertThat(
          subject.getReadOnlyStaleness(),
          is(equalTo(TimestampBound.ofMinReadTimestamp(Timestamp.MAX_VALUE))));
      subject.setAutocommit(false);
      assertThat(subject.getReadOnlyStaleness().getMode(), is(equalTo(TimestampBound.Mode.STRONG)));
      subject.setAutocommit(true);
      assertThat(subject.getReadOnlyStaleness().getMode(), is(equalTo(TimestampBound.Mode.STRONG)));

      subject.setReadOnlyStaleness(TimestampBound.ofMaxStaleness(10L, TimeUnit.SECONDS));
      assertThat(
          subject.getReadOnlyStaleness(),
          is(equalTo(TimestampBound.ofMaxStaleness(10L, TimeUnit.SECONDS))));
      subject.setAutocommit(false);
      assertThat(subject.getReadOnlyStaleness().getMode(), is(equalTo(TimestampBound.Mode.STRONG)));
      subject.setAutocommit(true);
      assertThat(subject.getReadOnlyStaleness().getMode(), is(equalTo(TimestampBound.Mode.STRONG)));
    }
  }

  @Test
  public void testChangeReadOnlyModeInAutocommit() {
    try (ConnectionImpl subject =
        createConnection(
            ConnectionOptions.newBuilder()
                .setCredentials(NoCredentials.getInstance())
                .setUri(URI)
                .build())) {
      subject.execute(Statement.of(UPDATE));
      assertThat(subject.getCommitTimestamp(), is(notNullValue()));

      // change to read-only
      subject.setReadOnly(true);
      expectSpannerException(
          "Updates should not be allowed in read-only mode",
          connection -> connection.execute(Statement.of(UPDATE)),
          subject);
      assertThat(subject.executeQuery(Statement.of(SELECT)), is(notNullValue()));

      // change back to read-write
      subject.setReadOnly(false);
      subject.execute(Statement.of(UPDATE));
      assertThat(subject.getCommitTimestamp(), is(notNullValue()));

      // and back to read-only
      subject.setReadOnly(true);
      expectSpannerException(
          "DDL should not be allowed in read-only mode",
          connection -> connection.execute(Statement.of(DDL)),
          subject);
      assertThat(subject.executeQuery(Statement.of(SELECT)), is(notNullValue()));
    }
  }

  @Test
  public void testChangeReadOnlyModeInTransactionalMode() {
    try (ConnectionImpl subject =
        createConnection(
            ConnectionOptions.newBuilder()
                .setCredentials(NoCredentials.getInstance())
                .setUri(URI)
                .build())) {
      subject.setAutocommit(false);

      subject.execute(Statement.of(UPDATE));
      subject.commit();
      assertThat(subject.getCommitTimestamp(), is(notNullValue()));

      // change to read-only
      subject.setReadOnly(true);
      expectSpannerException(
          "Updates should not be allowed in read-only mode",
          connection -> connection.execute(Statement.of(UPDATE)),
          subject);
      assertThat(subject.executeQuery(Statement.of(SELECT)), is(notNullValue()));
      subject.commit();

      // change back to read-write
      subject.setReadOnly(false);
      subject.execute(Statement.of(UPDATE));
      subject.commit();
      assertThat(subject.getCommitTimestamp(), is(notNullValue()));

      // and back to read-only
      subject.setReadOnly(true);
      expectSpannerException(
          "DDL should not be allowed in read-only mode",
          connection -> connection.execute(Statement.of(DDL)),
          subject);
      assertThat(subject.executeQuery(Statement.of(SELECT)), is(notNullValue()));
    }
  }

  @Test
  public void testAddRemoveTransactionRetryListener() {
    try (ConnectionImpl subject =
        createConnection(
            ConnectionOptions.newBuilder()
                .setCredentials(NoCredentials.getInstance())
                .setUri(URI)
                .build())) {
      assertThat(subject.getTransactionRetryListeners().hasNext(), is(false));
      TransactionRetryListener listener = mock(TransactionRetryListener.class);
      subject.addTransactionRetryListener(listener);
      assertThat(subject.getTransactionRetryListeners().hasNext(), is(true));
      assertThat(subject.removeTransactionRetryListener(listener), is(true));
      assertThat(subject.getTransactionRetryListeners().hasNext(), is(false));
      assertThat(subject.removeTransactionRetryListener(listener), is(false));
    }
  }

  @Test
  public void testMergeQueryOptions() {
    ConnectionOptions connectionOptions = mock(ConnectionOptions.class);
    SpannerPool spannerPool = mock(SpannerPool.class);
    DdlClient ddlClient = mock(DdlClient.class);
    DatabaseClient dbClient = mock(DatabaseClient.class);
    final UnitOfWork unitOfWork = mock(UnitOfWork.class);
    when(unitOfWork.executeQueryAsync(
            any(ParsedStatement.class), any(AnalyzeMode.class), Mockito.<QueryOption>any()))
        .thenReturn(ApiFutures.immediateFuture(mock(ResultSet.class)));
    try (ConnectionImpl impl =
        new ConnectionImpl(connectionOptions, spannerPool, ddlClient, dbClient) {
          @Override
          UnitOfWork getCurrentUnitOfWorkOrStartNewUnitOfWork() {
            return unitOfWork;
          }
        }) {
      // Execute query with an optimizer version and statistics package set on the connection.
      impl.setOptimizerVersion("1");
      impl.setOptimizerStatisticsPackage("custom-package-1");
      impl.executeQuery(Statement.of("SELECT FOO FROM BAR"));
      verify(unitOfWork)
          .executeQueryAsync(
              StatementParser.INSTANCE.parse(
                  Statement.newBuilder("SELECT FOO FROM BAR")
                      .withQueryOptions(
                          QueryOptions.newBuilder()
                              .setOptimizerVersion("1")
                              .setOptimizerStatisticsPackage("custom-package-1")
                              .build())
                      .build()),
              AnalyzeMode.NONE);

      // Execute query with an optimizer version and statistics package set on the connection.
      impl.setOptimizerVersion("2");
      impl.setOptimizerStatisticsPackage("custom-package-2");
      impl.executeQuery(Statement.of("SELECT FOO FROM BAR"));
      verify(unitOfWork)
          .executeQueryAsync(
              StatementParser.INSTANCE.parse(
                  Statement.newBuilder("SELECT FOO FROM BAR")
                      .withQueryOptions(
                          QueryOptions.newBuilder()
                              .setOptimizerVersion("2")
                              .setOptimizerStatisticsPackage("custom-package-2")
                              .build())
                      .build()),
              AnalyzeMode.NONE);

      // Execute query with an optimizer version and statistics package set on the connection and
      // PrefetchChunks query
      // option specified for the query.
      QueryOption prefetchOption = Options.prefetchChunks(100);
      impl.setOptimizerVersion("3");
      impl.setOptimizerStatisticsPackage("custom-package-3");
      impl.executeQuery(Statement.of("SELECT FOO FROM BAR"), prefetchOption);
      verify(unitOfWork)
          .executeQueryAsync(
              StatementParser.INSTANCE.parse(
                  Statement.newBuilder("SELECT FOO FROM BAR")
                      .withQueryOptions(
                          QueryOptions.newBuilder()
                              .setOptimizerVersion("3")
                              .setOptimizerStatisticsPackage("custom-package-3")
                              .build())
                      .build()),
              AnalyzeMode.NONE,
              prefetchOption);

      // Execute query with an optimizer version and statistics package set on the connection, and
      // the same options also
      // passed in to the query. The specific options passed in to the query should take precedence.
      impl.setOptimizerVersion("4");
      impl.setOptimizerStatisticsPackage("custom-package-4");
      impl.executeQuery(
          Statement.newBuilder("SELECT FOO FROM BAR")
              .withQueryOptions(
                  QueryOptions.newBuilder()
                      .setOptimizerVersion("5")
                      .setOptimizerStatisticsPackage("custom-package-5")
                      .build())
              .build(),
          prefetchOption);
      verify(unitOfWork)
          .executeQueryAsync(
              StatementParser.INSTANCE.parse(
                  Statement.newBuilder("SELECT FOO FROM BAR")
                      .withQueryOptions(
                          QueryOptions.newBuilder()
                              .setOptimizerVersion("5")
                              .setOptimizerStatisticsPackage("custom-package-5")
                              .build())
                      .build()),
              AnalyzeMode.NONE,
              prefetchOption);
    }
  }

  @Test
  public void testStatementTagAlwaysAllowed() {
    ConnectionOptions connectionOptions = mock(ConnectionOptions.class);
    when(connectionOptions.isAutocommit()).thenReturn(true);
    SpannerPool spannerPool = mock(SpannerPool.class);
    DdlClient ddlClient = mock(DdlClient.class);
    DatabaseClient dbClient = mock(DatabaseClient.class);
    final UnitOfWork unitOfWork = mock(UnitOfWork.class);
    when(unitOfWork.executeQueryAsync(
            any(ParsedStatement.class), any(AnalyzeMode.class), Mockito.<QueryOption>any()))
        .thenReturn(ApiFutures.immediateFuture(mock(ResultSet.class)));
    try (ConnectionImpl connection =
        new ConnectionImpl(connectionOptions, spannerPool, ddlClient, dbClient) {
          @Override
          UnitOfWork getCurrentUnitOfWorkOrStartNewUnitOfWork() {
            return unitOfWork;
          }
        }) {
      assertTrue(connection.isAutocommit());

      assertNull(connection.getStatementTag());
      connection.setStatementTag("tag");
      assertEquals("tag", connection.getStatementTag());
      connection.setStatementTag(null);
      assertNull(connection.getStatementTag());

      connection.setAutocommit(false);

      connection.setStatementTag("tag");
      assertEquals("tag", connection.getStatementTag());
      connection.setStatementTag(null);
      assertNull(connection.getStatementTag());

      // Start a transaction
      connection.execute(Statement.of("SELECT FOO FROM BAR"));
      connection.setStatementTag("tag");
      assertEquals("tag", connection.getStatementTag());
      connection.setStatementTag(null);
      assertNull(connection.getStatementTag());
    }
  }

  @Test
  public void testTransactionTagAllowedInTransaction() {
    ConnectionOptions connectionOptions = mock(ConnectionOptions.class);
    when(connectionOptions.isAutocommit()).thenReturn(false);
    SpannerPool spannerPool = mock(SpannerPool.class);
    DdlClient ddlClient = mock(DdlClient.class);
    DatabaseClient dbClient = mock(DatabaseClient.class);
    try (ConnectionImpl connection =
        new ConnectionImpl(connectionOptions, spannerPool, ddlClient, dbClient)) {
      assertFalse(connection.isAutocommit());

      assertNull(connection.getTransactionTag());
      connection.setTransactionTag("tag");
      assertEquals("tag", connection.getTransactionTag());
      connection.setTransactionTag(null);
      assertNull(connection.getTransactionTag());

      // Committing or rolling back a transaction should clear the transaction tag for the next
      // transaction.
      connection.setTransactionTag("tag");
      assertEquals("tag", connection.getTransactionTag());
      connection.commit();
      assertNull(connection.getTransactionTag());

      connection.setTransactionTag("tag");
      assertEquals("tag", connection.getTransactionTag());
      connection.rollback();
      assertNull(connection.getTransactionTag());

      // Temporary transactions should also allow transaction tags.
      connection.setAutocommit(false);
      connection.beginTransaction();
      assertNull(connection.getTransactionTag());
      connection.setTransactionTag("tag");
      assertEquals("tag", connection.getTransactionTag());
      connection.commit();
      assertNull(connection.getTransactionTag());
    }
  }

  @Test
  public void testTransactionTagNotAllowedWithoutTransaction() {
    ConnectionOptions connectionOptions = mock(ConnectionOptions.class);
    when(connectionOptions.isAutocommit()).thenReturn(true);
    SpannerPool spannerPool = mock(SpannerPool.class);
    DdlClient ddlClient = mock(DdlClient.class);
    DatabaseClient dbClient = mock(DatabaseClient.class);
    try (ConnectionImpl connection =
        new ConnectionImpl(connectionOptions, spannerPool, ddlClient, dbClient)) {
      assertTrue(connection.isAutocommit());

      try {
        connection.setTransactionTag("tag");
        fail("missing expected exception");
      } catch (SpannerException e) {
        assertEquals(ErrorCode.FAILED_PRECONDITION, e.getErrorCode());
      }
    }
  }

  @Test
  public void testTransactionTagNotAllowedAfterTransactionStarted() {
    ConnectionOptions connectionOptions = mock(ConnectionOptions.class);
    when(connectionOptions.isAutocommit()).thenReturn(false);
    SpannerPool spannerPool = mock(SpannerPool.class);
    DdlClient ddlClient = mock(DdlClient.class);
    DatabaseClient dbClient = mock(DatabaseClient.class);
    final UnitOfWork unitOfWork = mock(UnitOfWork.class);
    // Indicate that a transaction has been started.
    when(unitOfWork.getState()).thenReturn(UnitOfWorkState.STARTED);
    when(unitOfWork.executeQueryAsync(
            any(ParsedStatement.class), any(AnalyzeMode.class), Mockito.<QueryOption>any()))
        .thenReturn(ApiFutures.immediateFuture(mock(ResultSet.class)));
    when(unitOfWork.rollbackAsync()).thenReturn(ApiFutures.immediateFuture(null));
    try (ConnectionImpl connection =
        new ConnectionImpl(connectionOptions, spannerPool, ddlClient, dbClient) {
          @Override
          UnitOfWork createNewUnitOfWork() {
            return unitOfWork;
          }
        }) {
      // Start a transaction
      connection.execute(Statement.of("SELECT FOO FROM BAR"));
      try {
        connection.setTransactionTag("tag");
        fail("missing expected exception");
      } catch (SpannerException e) {
        assertEquals(ErrorCode.FAILED_PRECONDITION, e.getErrorCode());
      }
      assertNull(connection.getTransactionTag());
    }
  }
}
