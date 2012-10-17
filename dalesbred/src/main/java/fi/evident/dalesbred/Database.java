/*
 * Copyright (c) 2012 Evident Solutions Oy
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package fi.evident.dalesbred;

import fi.evident.dalesbred.connection.DataSourceConnectionProvider;
import fi.evident.dalesbred.connection.DriverManagerConnectionProvider;
import fi.evident.dalesbred.dialects.Dialect;
import fi.evident.dalesbred.instantiation.InstantiatorRegistry;
import fi.evident.dalesbred.results.*;
import fi.evident.dalesbred.support.proxy.TransactionalProxyFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static fi.evident.dalesbred.Propagation.*;
import static fi.evident.dalesbred.SqlQuery.query;
import static fi.evident.dalesbred.utils.Require.requireNonNull;

/**
 * The main abstraction of the library: represents a connection to database and provides a way to
 * execute callbacks in transactions.
 */
public final class Database {

    /** Provides us with connections whenever we need one */
    private final Provider<Connection> connectionProvider;

    /** The current active transaction of this thread, or null */
    private final ThreadLocal<DatabaseTransaction> activeTransaction = new ThreadLocal<DatabaseTransaction>();

    /** Logger in which we log actions */
    private final Logger log = Logger.getLogger(getClass().getName());

    /** The isolation level to use for transactions that have not specified an explicit level. Null for default. */
    @Nullable
    private Isolation defaultIsolation = null;

    /** Default propagation for new transactions */
    private boolean allowImplicitTransactions = true;

    /** The dialect that the database uses */
    private final Dialect dialect;

    /** Instantiators */
    private final InstantiatorRegistry instantiatorRegistry;

    /**
     * Returns a new Database that uses given {@link DataSource} to retrieve connections.
     */
    @NotNull
    public static Database forDataSource(@NotNull DataSource dataSource) {
        return new Database(new DataSourceConnectionProvider(dataSource));
    }

    /**
     * Returns a new Database that uses {@link DataSource} with given JNDI-name.
     */
    @NotNull
    public static Database forJndiDataSource(@NotNull String jndiName) {
        try {
            InitialContext ctx = new InitialContext();
            DataSource dataSource = (DataSource) ctx.lookup(jndiName);
            if (dataSource != null)
                return forDataSource(dataSource);
            else
                throw new DatabaseException("Could not find DataSource '" + jndiName + "'");
        } catch (NamingException e) {
            throw new DatabaseException("Error when looking up DataSource '" + jndiName + "': " + e, e);
        }
    }

    /**
     * Returns a new Database that uses given connection options to open connection. The database
     * uses {@link DriverManagerConnectionProvider} so it performs no connection pooling.
     *
     * @see DriverManagerConnectionProvider
     */
    @NotNull
    public static Database forUrlAndCredentials(@NotNull String url, String username, String password) {
        return new Database(new DriverManagerConnectionProvider(url, username, password));
    }

    /**
     * Constructs a new Database that uses given connection-provider and auto-detects the dialect to use.
     */
    @Inject
    public Database(@NotNull Provider<Connection> connectionProvider) {
        this(connectionProvider, Dialect.detect(connectionProvider));
    }

    /**
     * Constructs a new Database that uses given connection-provider and dialect.
     */
    public Database(@NotNull Provider<Connection> connectionProvider, @NotNull Dialect dialect) {
        this.connectionProvider = requireNonNull(connectionProvider);
        this.dialect = requireNonNull(dialect);
        this.instantiatorRegistry = new InstantiatorRegistry(dialect);
    }

    /**
     * Executes a block of code within a context of a transaction, using {@link Propagation#REQUIRED} propagation.
     */
    public <T> T withTransaction(@NotNull TransactionCallback<T> callback) {
        return withTransaction(REQUIRED, defaultIsolation, callback);
    }

    /**
     * Executes a block of code with given propagation and configuration default isolation.
     */
    public <T> T withTransaction(@NotNull Propagation propagation, @NotNull TransactionCallback<T> callback) {
        return withTransaction(propagation, defaultIsolation, callback);
    }

    /**
     * Executes a block of code with given propagation and isolation.
     */
    public <T> T withTransaction(@NotNull Propagation propagation,
                                 @Nullable Isolation isolation,
                                 @NotNull TransactionCallback<T> callback) {

        TransactionSettings settings = new TransactionSettings();
        settings.setPropagation(propagation);
        settings.setIsolation(isolation);

        return withTransaction(settings, callback);
    }

    /**
     * Executes a block of code with given transaction settings.
     *
     * @see TransactionSettings
     */
    public <T> T withTransaction(@NotNull TransactionSettings settings,
                                 @NotNull TransactionCallback<T> callback) {

        Propagation propagation = settings.getPropagation();
        Isolation isolation = settings.getIsolation();
        int retries = settings.getRetries();

        DatabaseTransaction existingTransaction = activeTransaction.get();

        if (existingTransaction != null) {
            if (propagation == REQUIRES_NEW)
                return withSuspendedTransaction(isolation, callback);
            else if (propagation == NESTED)
                return existingTransaction.nested(retries, callback);
            else
                return existingTransaction.join(callback);

        } else {
            if (propagation == MANDATORY)
                throw new NoActiveTransactionException("Transaction propagation was MANDATORY, but there was no existing transaction.");

            DatabaseTransaction newTransaction = new DatabaseTransaction(connectionProvider, dialect, isolation);
            try {
                activeTransaction.set(newTransaction);
                return newTransaction.execute(retries, callback);
            } finally {
                activeTransaction.set(null);
                newTransaction.close();
            }
        }
    }

    /**
     * Returns true if and only if the current thread has an active transaction for this database.
     */
    public boolean hasActiveTransaction() {
        return activeTransaction.get() != null;
    }

    private <T> T withSuspendedTransaction(@Nullable Isolation isolation, @NotNull TransactionCallback<T> callback) {
        DatabaseTransaction suspended = activeTransaction.get();
        try {
            activeTransaction.set(null);
            return withTransaction(REQUIRED, isolation, callback);
        } finally {
            activeTransaction.set(suspended);
        }
    }

    /**
     * Executes the block of code within context of current transaction. If there's no transaction in progress
     * throws {@link IllegalStateException} unless implicit transaction are allowed: in this case, starts a new
     * transaction.
     *
     * @throws IllegalStateException if there's no active transaction.
     * @see #setAllowImplicitTransactions(boolean)
     */
    private <T> T withCurrentTransaction(@NotNull TransactionCallback<T> callback) {
        if (allowImplicitTransactions) {
            return withTransaction(callback);
        } else {
            DatabaseTransaction transaction = activeTransaction.get();
            if (transaction != null)
                return transaction.join(callback);
            else
                throw new NoActiveTransactionException("Tried to perform database operation without active transaction. Database accesses should be bracketed with Database.withTransaction(...) or implicit transactions should be enabled.");
        }
    }

    /**
     * Executes a query and processes the results with given {@link ResultSetProcessor}.
     * All other findXXX-methods are just convenience methods for this one.
     */
    public <T> T executeQuery(@NotNull final ResultSetProcessor<T> processor, @NotNull final SqlQuery query) {
        return withCurrentTransaction(new TransactionCallback<T>() {
            @Override
            public T execute(TransactionContext tx) throws SQLException {
                logQuery(query);

                PreparedStatement ps = tx.getConnection().prepareStatement(query.sql);
                try {
                    bindArguments(ps, query.args);

                    return processResults(ps.executeQuery(), processor);
                } finally {
                    ps.close();
                }
            }
        });
    }

    public <T> T executeQuery(@NotNull ResultSetProcessor<T> processor, @NotNull @SQL String sql, Object... args) {
        return executeQuery(processor, query(sql, args));
    }

    /**
     * Executes a query and processes each row of the result with given {@link RowMapper}
     * to produce a list of results.
     */
    @NotNull
    public <T> List<T> findAll(@NotNull RowMapper<T> rowMapper, @NotNull SqlQuery query) {
        return executeQuery(new ListWithRowMapperResultSetProcessor<T>(rowMapper), query);
    }

    @NotNull
    public <T> List<T> findAll(@NotNull RowMapper<T> rowMapper, @NotNull @SQL String sql, Object... args) {
        return findAll(rowMapper, query(sql, args));
    }

    /**
     * Executes a query and converts the results to instances of given class using default mechanisms.
     */
    @NotNull
    public <T> List<T> findAll(@NotNull Class<T> cl, @NotNull SqlQuery query) {
        return executeQuery(resultProcessorForClass(cl), query);
    }

    /**
     * Executes a query and converts the results to instances of given class using default mechanisms.
     */
    @NotNull
    public <T> List<T> findAll(@NotNull Class<T> cl, @NotNull @SQL String sql, Object... args) {
        return findAll(cl, query(sql, args));
    }

    /**
     * Finds a unique result from database, using given {@link RowMapper} to convert the row.
     *
     * @throws NonUniqueResultException if there are no rows or multiple rows
     */
    public <T> T findUnique(@NotNull RowMapper<T> mapper, @NotNull SqlQuery query) {
        return unique(findAll(mapper, query));
    }

    public <T> T findUnique(@NotNull RowMapper<T> mapper, @NotNull @SQL String sql, Object... args) {
        return findUnique(mapper, query(sql, args));
    }

    /**
     * Finds a unique result from database, converting the database row to given class using default mechanisms.
     *
     * @throws NonUniqueResultException if there are no rows or multiple rows
     */
    public <T> T findUnique(@NotNull Class<T> cl, @NotNull SqlQuery query) {
        return unique(findAll(cl, query));
    }

    public <T> T findUnique(@NotNull Class<T> cl, @NotNull @SQL String sql, Object... args) {
        return findUnique(cl, query(sql, args));
    }

    /**
     * Find a unique result from database, using given {@link RowMapper} to convert row. Returns null if
     * there are no results.
     *
     * @throws NonUniqueResultException if there are multiple result rows
     */
    @Nullable
    public <T> T findUniqueOrNull(@NotNull RowMapper<T> rowMapper, @NotNull SqlQuery query) {
        return uniqueOrNull(findAll(rowMapper, query));
    }

    @Nullable
    public <T> T findUniqueOrNull(@NotNull RowMapper<T> rowMapper, @NotNull @SQL String sql, Object... args) {
        return findUniqueOrNull(rowMapper, query(sql, args));
    }

    /**
     * Finds a unique result from database, converting the database row to given class using default mechanisms.
     * Returns null if there are no results.
     *
     * @throws NonUniqueResultException if there are multiple result rows
     */
    @Nullable
    public <T> T findUniqueOrNull(@NotNull Class<T> cl, @NotNull SqlQuery query) {
        return uniqueOrNull(findAll(cl, query));
    }

    public <T> T findUniqueOrNull(@NotNull Class<T> cl, @NotNull @SQL String sql, Object... args) {
        return findUniqueOrNull(cl, query(sql, args));
    }

    /**
     * A convenience method for retrieving a single non-null integer.
     */
    public int findUniqueInt(@NotNull SqlQuery query) {
        Integer value = findUnique(Integer.class, query);
        if (value != null)
            return value;
        else
            throw new UnexpectedResultException("database returned null instead of int");
    }

    /**
     * A convenience method for retrieving a single non-null integer.
     */
    public int findUniqueInt(@NotNull @SQL String sql, Object... args) {
        return findUniqueInt(query(sql, args));
    }

    /**
     * A convenience method for retrieving a single non-null long.
     */
    public long findUniqueLong(@NotNull SqlQuery query) {
        Long value = findUnique(Long.class, query);
        if (value != null)
            return value;
        else
            throw new UnexpectedResultException("database returned null instead of long");
    }

    /**
     * A convenience method for retrieving a single non-null integer.
     */
    public long findUniqueLong(@NotNull @SQL String sql, Object... args) {
        return findUniqueLong(query(sql, args));
    }

    @NotNull
    public <K,V> Map<K, V> findMap(@NotNull final Class<K> keyType,
                                   @NotNull final Class<V> valueType,
                                   @NotNull SqlQuery query) {
        return executeQuery(new MapResultSetProcessor<K, V>(keyType, valueType, instantiatorRegistry), query);
    }

    @NotNull
    public <K,V> Map<K, V> findMap(@NotNull Class<K> keyType,
                                   @NotNull Class<V> valueType,
                                   @NotNull @SQL String sql,
                                   Object... args) {
        return findMap(keyType, valueType, query(sql, args));
    }

    /**
     * Executes a query and creates a {@link ResultTable} from the results.
     */
    @NotNull
    public ResultTable findTable(@NotNull SqlQuery query) {
        return executeQuery(new ResultTableResultSetProcessor(), query);
    }

    @NotNull
    public ResultTable findTable(@NotNull @SQL String sql, Object... args) {
        return findTable(query(sql, args));
    }

    /**
     * Executes an update against the database and returns the amount of affected rows.
     */
    public int update(@NotNull final SqlQuery query) {
        return withCurrentTransaction(new TransactionCallback<Integer>() {
            @Override
            public Integer execute(TransactionContext tx) throws SQLException {
                logQuery(query);

                PreparedStatement ps = tx.getConnection().prepareStatement(query.sql);
                try {
                    bindArguments(ps, query.args);
                    return ps.executeUpdate();
                } finally {
                    ps.close();
                }
            }
        });
    }

    /**
     * Executes an update against the database and returns the amount of affected rows.
     */
    public int update(@NotNull @SQL String sql, Object... args) {
        return update(query(sql, args));
    }

    private void logQuery(@NotNull SqlQuery query) {
        if (log.isLoggable(Level.FINE))
            log.fine("executing query " + query);
    }

    private void bindArguments(@NotNull PreparedStatement ps, @NotNull List<?> args) throws SQLException {
        InstantiatorRegistry instantiatorRegistry = this.instantiatorRegistry;
        int i = 1;

        for (Object arg : args)
            ps.setObject(i++, instantiatorRegistry.valueToDatabase(arg));
    }

    @Nullable
    private static <T> T uniqueOrNull(@NotNull List<T> items) {
        switch (items.size()) {
            case 0:  return null;
            case 1:  return items.get(0);
            default: throw new NonUniqueResultException(items.size());
        }
    }

    private static <T> T unique(@NotNull List<T> items) {
        if (items.size() == 1)
            return items.get(0);
        else
            throw new NonUniqueResultException(items.size());
    }

    @NotNull
    private <T> ResultSetProcessor<List<T>> resultProcessorForClass(@NotNull Class<T> cl) {
        return new ReflectionResultSetProcessor<T>(cl, instantiatorRegistry);
    }

    private static <T> T processResults(@NotNull ResultSet resultSet, @NotNull ResultSetProcessor<T> processor) throws SQLException {
        try {
            return processor.process(resultSet);
        } finally {
            resultSet.close();
        }
    }

    /**
     * Returns a transactional proxy for given object.
     */
    @NotNull
    public <T> T createTransactionalProxyFor(@NotNull Class<T> iface, @NotNull T target) {
        return TransactionalProxyFactory.createTransactionalProxyFor(this, iface, target);
    }

    /**
     * Returns the used transaction isolation level, or null for default level.
     */
    @Nullable
    public Isolation getDefaultIsolation() {
        return defaultIsolation;
    }

    /**
     * Sets the transaction isolation level to use, or null for default level
     */
    public void setDefaultIsolation(@Nullable Isolation isolation) {
        this.defaultIsolation = isolation;
    }

    public boolean isAllowImplicitTransactions() {
        return allowImplicitTransactions;
    }

    /**
     * If flag is set to true (by default it's false) queries without active transaction will
     * not throw exception but will start a fresh transaction.
     */
    public void setAllowImplicitTransactions(boolean allowImplicitTransactions) {
        this.allowImplicitTransactions = allowImplicitTransactions;
    }

    @Override
    @NotNull
    public String toString() {
        return "Database [dialect=" + dialect + ", allowImplicitTransactions=" + allowImplicitTransactions + ", defaultIsolation=" + defaultIsolation + "]";
    }
}
