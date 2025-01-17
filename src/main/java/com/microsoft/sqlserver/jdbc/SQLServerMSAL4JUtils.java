/*
 * Microsoft JDBC Driver for SQL Server Copyright(c) Microsoft Corporation All rights reserved. This program is made
 * available under the terms of the MIT License. See the LICENSE file in the project root for more information.
 */

package com.microsoft.sqlserver.jdbc;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;

import java.text.MessageFormat;

import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;

import javax.security.auth.kerberos.KerberosPrincipal;

import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import com.microsoft.aad.msal4j.IAccount;
import com.microsoft.aad.msal4j.ClientCredentialFactory;
import com.microsoft.aad.msal4j.ClientCredentialParameters;
import com.microsoft.aad.msal4j.ConfidentialClientApplication;
import com.microsoft.aad.msal4j.IAuthenticationResult;
import com.microsoft.aad.msal4j.IClientCredential;
import com.microsoft.aad.msal4j.IntegratedWindowsAuthenticationParameters;
import com.microsoft.aad.msal4j.InteractiveRequestParameters;
import com.microsoft.aad.msal4j.MsalInteractionRequiredException;
import com.microsoft.aad.msal4j.PublicClientApplication;
import com.microsoft.aad.msal4j.SilentParameters;
import com.microsoft.aad.msal4j.SystemBrowserOptions;
import com.microsoft.aad.msal4j.UserNamePasswordParameters;

import com.microsoft.sqlserver.jdbc.SQLServerConnection.ActiveDirectoryAuthentication;
import com.microsoft.sqlserver.jdbc.SQLServerConnection.SqlFedAuthInfo;


class SQLServerMSAL4JUtils {

    static final String REDIRECTURI = "http://localhost";
    static final String SLASH_DEFAULT = "/.default";
    static final String ACCESS_TOKEN_EXPIRE = "access token expires: ";

    private final static String LOGCONTEXT = "MSAL version "
            + com.microsoft.aad.msal4j.PublicClientApplication.class.getPackage().getImplementationVersion() + ": ";

    private static final java.util.logging.Logger logger = java.util.logging.Logger
            .getLogger("com.microsoft.sqlserver.jdbc.SQLServerMSAL4JUtils");

    private SQLServerMSAL4JUtils() {
        throw new UnsupportedOperationException(SQLServerException.getErrString("R_notSupported"));
    }

    private static final Lock lock = new ReentrantLock();

    static SqlAuthenticationToken getSqlFedAuthToken(SqlFedAuthInfo fedAuthInfo, String user, String password,
            String authenticationString) throws SQLServerException {
        ExecutorService executorService = Executors.newSingleThreadExecutor();

        if (logger.isLoggable(Level.FINEST)) {
            logger.finest(LOGCONTEXT + authenticationString + ": get FedAuth token for user: " + user);
        }

        lock.lock();

        try {
            final PublicClientApplication pca = PublicClientApplication
                    .builder(ActiveDirectoryAuthentication.JDBC_FEDAUTH_CLIENT_ID).executorService(executorService)
                    .setTokenCacheAccessAspect(PersistentTokenCacheAccessAspect.getInstance())
                    .authority(fedAuthInfo.stsurl).build();

            final CompletableFuture<IAuthenticationResult> future = pca.acquireToken(UserNamePasswordParameters
                    .builder(Collections.singleton(fedAuthInfo.spn + SLASH_DEFAULT), user, password.toCharArray())
                    .build());

            final IAuthenticationResult authenticationResult = future.get();

            if (logger.isLoggable(Level.FINEST)) {
                logger.finest(
                        LOGCONTEXT + (authenticationResult.account() != null ? authenticationResult.account().username()
                                + ": " : "" + ACCESS_TOKEN_EXPIRE + authenticationResult.expiresOnDate()));
            }

            return new SqlAuthenticationToken(authenticationResult.accessToken(), authenticationResult.expiresOnDate());
        } catch (InterruptedException e) {
            // re-interrupt thread
            Thread.currentThread().interrupt();

            throw new SQLServerException(e.getMessage(), e);
        } catch (MalformedURLException | ExecutionException e) {
            throw getCorrectedException(e, user, authenticationString);
        } finally {
            lock.unlock();
            executorService.shutdown();
        }
    }

    static SqlAuthenticationToken getSqlFedAuthTokenPrincipal(SqlFedAuthInfo fedAuthInfo, String aadPrincipalID,
            String aadPrincipalSecret, String authenticationString) throws SQLServerException {
        ExecutorService executorService = Executors.newSingleThreadExecutor();

        if (logger.isLoggable(Level.FINEST)) {
            logger.finest(LOGCONTEXT + authenticationString + ": get FedAuth token for principal: " + aadPrincipalID);
        }

        String defaultScopeSuffix = SLASH_DEFAULT;
        String scope = fedAuthInfo.spn.endsWith(defaultScopeSuffix) ? fedAuthInfo.spn
                                                                    : fedAuthInfo.spn + defaultScopeSuffix;
        Set<String> scopes = new HashSet<>();
        scopes.add(scope);

        lock.lock();

        try {
            IClientCredential credential = ClientCredentialFactory.createFromSecret(aadPrincipalSecret);
            ConfidentialClientApplication clientApplication = ConfidentialClientApplication
                    .builder(aadPrincipalID, credential).executorService(executorService)
                    .setTokenCacheAccessAspect(PersistentTokenCacheAccessAspect.getInstance())
                    .authority(fedAuthInfo.stsurl).build();

            final CompletableFuture<IAuthenticationResult> future = clientApplication
                    .acquireToken(ClientCredentialParameters.builder(scopes).build());
            final IAuthenticationResult authenticationResult = future.get();

            if (logger.isLoggable(Level.FINEST)) {
                logger.finest(
                        LOGCONTEXT + (authenticationResult.account() != null ? authenticationResult.account().username()
                                + ": " : "" + ACCESS_TOKEN_EXPIRE + authenticationResult.expiresOnDate()));
            }

            return new SqlAuthenticationToken(authenticationResult.accessToken(), authenticationResult.expiresOnDate());
        } catch (InterruptedException e) {
            // re-interrupt thread
            Thread.currentThread().interrupt();

            throw new SQLServerException(e.getMessage(), e);
        } catch (MalformedURLException | ExecutionException e) {
            throw getCorrectedException(e, aadPrincipalID, authenticationString);
        } finally {
            lock.unlock();
            executorService.shutdown();
        }
    }

    static SqlAuthenticationToken getSqlFedAuthTokenPrincipalCertificate(SqlFedAuthInfo fedAuthInfo,
            String aadPrincipalID, String certFile, String certPassword, String certKey, String certKeyPassword,
            String authenticationString) throws SQLServerException {
        ExecutorService executorService = Executors.newSingleThreadExecutor();

        if (logger.isLoggable(Level.FINEST)) {
            logger.finest(LOGCONTEXT + authenticationString + ": get FedAuth token for principal certificate: "
                    + aadPrincipalID);
        }

        String defaultScopeSuffix = SLASH_DEFAULT;
        String scope = fedAuthInfo.spn.endsWith(defaultScopeSuffix) ? fedAuthInfo.spn
                                                                    : fedAuthInfo.spn + defaultScopeSuffix;
        Set<String> scopes = new HashSet<>();
        scopes.add(scope);

        lock.lock();

        try {
            ConfidentialClientApplication clientApplication = null;

            // check if cert is PKCS12 first
            try (InputStream is = new FileInputStream(certFile)) {
                KeyStore keyStore = SQLServerCertificateUtils.loadPKCS12KeyStore(certFile, certPassword);

                if (logger.isLoggable(Level.FINEST)) {
                    logger.finest(LOGCONTEXT + "certificate type: " + keyStore.getType());

                    // we don't need to do this unless logging enabled since MSAL will fail if cert is not valid
                    Enumeration<String> enumeration = keyStore.aliases();
                    while (enumeration.hasMoreElements()) {
                        String alias = enumeration.nextElement();
                        X509Certificate cert = (X509Certificate) keyStore.getCertificate(alias);
                        cert.checkValidity();
                        logger.finest(LOGCONTEXT + "certificate: " + cert.toString());
                    }
                }

                IClientCredential credential = ClientCredentialFactory.createFromCertificate(is, certPassword);
                clientApplication = ConfidentialClientApplication.builder(aadPrincipalID, credential)
                        .executorService(executorService)
                        .setTokenCacheAccessAspect(PersistentTokenCacheAccessAspect.getInstance())
                        .authority(fedAuthInfo.stsurl).build();
            } catch (FileNotFoundException e) {
                // re-throw if file not there no point to try another format
                throw new SQLServerException(SQLServerException.getErrString("R_readCertError") + e.getMessage(), null,
                        0, null);
            } catch (CertificateException | NoSuchAlgorithmException | IOException e) {
                // ignore not PKCS12 cert error, will try another format after this
                if (logger.isLoggable(Level.FINEST)) {
                    logger.finest(LOGCONTEXT + "Error loading PKCS12 certificate: " + e.getMessage());
                }
            }

            if (clientApplication == null) {
                // try loading X509 cert
                X509Certificate cert = (X509Certificate) SQLServerCertificateUtils.loadCertificate(certFile);

                if (logger.isLoggable(Level.FINEST)) {
                    logger.finest(LOGCONTEXT + "certificate type: " + cert.getType());

                    // we don't really need to do this, MSAL will fail if cert is not valid, but good to check here and throw with proper error message
                    cert.checkValidity();
                    logger.finest(LOGCONTEXT + "certificate: " + cert.toString());
                }

                PrivateKey privateKey = SQLServerCertificateUtils.loadPrivateKey(certKey, certKeyPassword);

                IClientCredential credential = ClientCredentialFactory.createFromCertificate(privateKey, cert);
                clientApplication = ConfidentialClientApplication.builder(aadPrincipalID, credential)
                        .executorService(executorService)
                        .setTokenCacheAccessAspect(PersistentTokenCacheAccessAspect.getInstance())
                        .authority(fedAuthInfo.stsurl).build();
            }

            final CompletableFuture<IAuthenticationResult> future = clientApplication
                    .acquireToken(ClientCredentialParameters.builder(scopes).build());
            final IAuthenticationResult authenticationResult = future.get();

            if (logger.isLoggable(Level.FINEST)) {
                logger.finest(
                        LOGCONTEXT + (authenticationResult.account() != null ? authenticationResult.account().username()
                                + ": " : "" + ACCESS_TOKEN_EXPIRE + authenticationResult.expiresOnDate()));
            }

            return new SqlAuthenticationToken(authenticationResult.accessToken(), authenticationResult.expiresOnDate());
        } catch (InterruptedException e) {
            // re-interrupt thread
            Thread.currentThread().interrupt();

            throw new SQLServerException(e.getMessage(), e);
        } catch (GeneralSecurityException e) {
            // this includes all certificate exceptions
            throw new SQLServerException(SQLServerException.getErrString("R_readCertError") + e.getMessage(), null, 0,
                    null);
        } catch (Exception e) {
            throw getCorrectedException(e, aadPrincipalID, authenticationString);

        } finally {
            lock.unlock();
            executorService.shutdown();
        }
    }

    static SqlAuthenticationToken getSqlFedAuthTokenIntegrated(SqlFedAuthInfo fedAuthInfo,
            String authenticationString) throws SQLServerException {
        ExecutorService executorService = Executors.newSingleThreadExecutor();

        /*
         * principal name does not matter, what matters is the realm name it gets the username in
         * principal_name@realm_name format
         */
        KerberosPrincipal kerberosPrincipal = new KerberosPrincipal("username");
        String user = kerberosPrincipal.getName();

        if (logger.isLoggable(Level.FINEST)) {
            logger.finest(LOGCONTEXT + authenticationString + ": get FedAuth token integrated, user: " + user
                    + "realm name:" + kerberosPrincipal.getRealm());
        }

        lock.lock();

        try {
            final PublicClientApplication pca = PublicClientApplication
                    .builder(ActiveDirectoryAuthentication.JDBC_FEDAUTH_CLIENT_ID).executorService(executorService)
                    .setTokenCacheAccessAspect(PersistentTokenCacheAccessAspect.getInstance())
                    .authority(fedAuthInfo.stsurl).build();

            final CompletableFuture<IAuthenticationResult> future = pca
                    .acquireToken(IntegratedWindowsAuthenticationParameters
                            .builder(Collections.singleton(fedAuthInfo.spn + SLASH_DEFAULT), user).build());

            final IAuthenticationResult authenticationResult = future.get();

            if (logger.isLoggable(Level.FINEST)) {
                logger.finest(
                        LOGCONTEXT + (authenticationResult.account() != null ? authenticationResult.account().username()
                                + ": " : "" + ACCESS_TOKEN_EXPIRE + authenticationResult.expiresOnDate()));
            }

            return new SqlAuthenticationToken(authenticationResult.accessToken(), authenticationResult.expiresOnDate());
        } catch (InterruptedException e) {
            // re-interrupt thread
            Thread.currentThread().interrupt();

            throw new SQLServerException(e.getMessage(), e);
        } catch (IOException | ExecutionException e) {
            throw getCorrectedException(e, user, authenticationString);
        } finally {
            lock.unlock();
            executorService.shutdown();
        }
    }

    static SqlAuthenticationToken getSqlFedAuthTokenInteractive(SqlFedAuthInfo fedAuthInfo, String user,
            String authenticationString) throws SQLServerException {
        ExecutorService executorService = Executors.newSingleThreadExecutor();

        if (logger.isLoggable(Level.FINEST)) {
            logger.finest(LOGCONTEXT + authenticationString + ": get FedAuth token interactive for user: " + user);
        }

        lock.lock();

        try {
            PublicClientApplication pca = PublicClientApplication
                    .builder(ActiveDirectoryAuthentication.JDBC_FEDAUTH_CLIENT_ID).executorService(executorService)
                    .setTokenCacheAccessAspect(PersistentTokenCacheAccessAspect.getInstance())
                    .authority(fedAuthInfo.stsurl).build();

            CompletableFuture<IAuthenticationResult> future = null;
            IAuthenticationResult authenticationResult = null;

            // try to acquire token silently if user account found in cache
            try {
                Set<IAccount> accountsInCache = pca.getAccounts().join();
                if (logger.isLoggable(Level.FINEST)) {
                    StringBuilder acc = new StringBuilder();
                    if (accountsInCache != null) {
                        for (IAccount account : accountsInCache) {
                            if (acc.length() != 0) {
                                acc.append(", ");
                            }
                            acc.append(account.username());
                        }
                    }
                    logger.finest(LOGCONTEXT + "Accounts in cache = " + acc + ", size = "
                            + (accountsInCache == null ? null : accountsInCache.size()) + ", user = " + user);
                }
                if (null != accountsInCache && !accountsInCache.isEmpty() && null != user && !user.isEmpty()) {
                    IAccount account = getAccountByUsername(accountsInCache, user);
                    if (null != account) {
                        if (logger.isLoggable(Level.FINEST)) {
                            logger.finest(LOGCONTEXT + "Silent authentication for user:" + user);
                        }
                        SilentParameters silentParameters = SilentParameters
                                .builder(Collections.singleton(fedAuthInfo.spn + SLASH_DEFAULT), account).build();

                        future = pca.acquireTokenSilently(silentParameters);
                    }
                }
            } catch (MsalInteractionRequiredException e) {
                // not an error, need to get token interactively
                if (logger.isLoggable(Level.FINEST)) {
                    logger.log(Level.FINEST, e,
                            () -> LOGCONTEXT + "Need to get token interactively: " + e.reason().toString());
                }
            }

            if (null != future) {
                authenticationResult = future.get();
            } else {
                // acquire token interactively with system browser
                if (logger.isLoggable(Level.FINEST)) {
                    logger.finest(LOGCONTEXT + "Interactive authentication");
                }
                InteractiveRequestParameters parameters = InteractiveRequestParameters.builder(new URI(REDIRECTURI))
                        .systemBrowserOptions(SystemBrowserOptions.builder()
                                .htmlMessageSuccess(SQLServerResource.getResource("R_MSALAuthComplete")).build())
                        .loginHint(user).scopes(Collections.singleton(fedAuthInfo.spn + SLASH_DEFAULT)).build();

                future = pca.acquireToken(parameters);
                authenticationResult = future.get();
            }

            if (logger.isLoggable(Level.FINEST)) {
                logger.finest(
                        LOGCONTEXT + (authenticationResult.account() != null ? authenticationResult.account().username()
                                + ": " : "" + ACCESS_TOKEN_EXPIRE + authenticationResult.expiresOnDate()));
            }

            return new SqlAuthenticationToken(authenticationResult.accessToken(), authenticationResult.expiresOnDate());
        } catch (InterruptedException e) {
            // re-interrupt thread
            Thread.currentThread().interrupt();

            throw new SQLServerException(e.getMessage(), e);
        } catch (MalformedURLException | URISyntaxException | ExecutionException e) {
            throw getCorrectedException(e, user, authenticationString);
        } finally {
            lock.unlock();
            executorService.shutdown();
        }
    }

    // Helper function to return account containing user name from set of accounts, or null if no match
    private static IAccount getAccountByUsername(Set<IAccount> accounts, String username) {
        if (!accounts.isEmpty()) {
            for (IAccount account : accounts) {
                if (account.username().equalsIgnoreCase(username)) {
                    return account;
                }
            }
        }
        return null;
    }

    private static SQLServerException getCorrectedException(Exception e, String user, String authenticationString) {
        Object[] msgArgs = {user, authenticationString};

        if (null == e.getCause() || null == e.getCause().getMessage()) {
            MessageFormat form = new MessageFormat(
                    SQLServerException.getErrString("R_MSALExecution") + " " + e.getMessage());

            // The case when Future's outcome has no AuthenticationResult but Exception.
            return new SQLServerException(form.format(msgArgs), null);
        } else {
            /*
             * the cause error message uses \\n\\r which does not give correct format change it to \r\n to provide
             * correct format. Also replace {} which confuses MessageFormat
             */
            String correctedErrorMessage = e.getCause().getMessage().replaceAll("\\\\r\\\\n", "\r\n")
                    .replaceAll("\\{", "\"").replaceAll("\\}", "\"");

            RuntimeException correctedAuthenticationException = new RuntimeException(correctedErrorMessage);
            MessageFormat form = new MessageFormat(
                    SQLServerException.getErrString("R_MSALExecution") + " " + correctedErrorMessage);

            /*
             * SQLServerException is caused by ExecutionException, which is caused by AuthenticationException to match
             * the exception tree before error message correction
             */
            ExecutionException correctedExecutionException = new ExecutionException(correctedAuthenticationException);

            return new SQLServerException(form.format(msgArgs), null, 0, correctedExecutionException);
        }
    }
}
