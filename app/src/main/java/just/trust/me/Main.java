package just.trust.me;

import static de.robv.android.xposed.XC_MethodReplacement.DO_NOTHING;
import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.callStaticMethod;
import static de.robv.android.xposed.XposedHelpers.findAndHookConstructor;
import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClass;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static de.robv.android.xposed.XposedHelpers.newInstance;
import static de.robv.android.xposed.XposedHelpers.setObjectField;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.net.http.SslError;
import android.net.http.X509TrustManagerExtensions;
import android.os.Build;
import android.util.Log;
import android.webkit.SslErrorHandler;
import android.webkit.WebView;

import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.scheme.HostNameResolver;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.SingleClientConnManager;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.http.params.HttpParams;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class Main implements IXposedHookLoadPackage {

    private static final String TAG = "JustTrustMe";
    String currentPackageName = "";

    public void handleLoadPackage(final LoadPackageParam lpparam) throws Throwable {

        currentPackageName = lpparam.packageName;
//        FlutterSSLHook.init();

        /* Apache Hooks */
        /* external/apache-http/src/org/apache/http/impl/client/DefaultHttpClient.java */
        /* public DefaultHttpClient() */
        if (hasDefaultHTTPClient()) {
            Log.d(TAG, "Hooking DefaultHTTPClient for: " + currentPackageName);
            findAndHookConstructor(DefaultHttpClient.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {

                    setObjectField(param.thisObject, "defaultParams", null);
                    setObjectField(param.thisObject, "connManager", getSCCM());
                }
            });

            /* external/apache-http/src/org/apache/http/impl/client/DefaultHttpClient.java */
            /* public DefaultHttpClient(HttpParams params) */
            Log.d(TAG, "Hooking DefaultHTTPClient(HttpParams) for: " + currentPackageName);
            findAndHookConstructor(DefaultHttpClient.class, HttpParams.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {

                    setObjectField(param.thisObject, "defaultParams", (HttpParams) param.args[0]);
                    setObjectField(param.thisObject, "connManager", getSCCM());
                }
            });

            /* external/apache-http/src/org/apache/http/impl/client/DefaultHttpClient.java */
            /* public DefaultHttpClient(ClientConnectionManager conman, HttpParams params) */
            Log.d(TAG, "Hooking DefaultHTTPClient(ClientConnectionManager, HttpParams) for: " + currentPackageName);
            findAndHookConstructor(DefaultHttpClient.class, ClientConnectionManager.class, HttpParams.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {

                    HttpParams params = (HttpParams) param.args[1];

                    setObjectField(param.thisObject, "defaultParams", params);
                    setObjectField(param.thisObject, "connManager", getCCM(param.args[0], params));
                }
            });
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            findAndHookMethod(X509TrustManagerExtensions.class, "checkServerTrusted", X509Certificate[].class, String.class, String.class, new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                    return param.args[0];
                }
            });
        }

        findAndHookMethod("android.security.net.config.NetworkSecurityTrustManager", lpparam.classLoader, "checkPins", List.class, DO_NOTHING);

        /* external/apache-http/src/org/apache/http/conn/ssl/SSLSocketFactory.java */
        /* public SSLSocketFactory( ... ) */
        try {
            Log.d(TAG, "Hooking SSLSocketFactory(String, KeyStore, String, KeyStore) for: " + currentPackageName);
            findAndHookConstructor(SSLSocketFactory.class, String.class, KeyStore.class, String.class, KeyStore.class,
                    SecureRandom.class, HostNameResolver.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {

                            String algorithm = (String) param.args[0];
                            KeyStore keystore = (KeyStore) param.args[1];
                            String keystorePassword = (String) param.args[2];
                            SecureRandom random = (SecureRandom) param.args[4];

                            KeyManager[] keymanagers = null;
                            TrustManager[] trustmanagers = null;

                            if (keystore != null) {
                                keymanagers = (KeyManager[]) callStaticMethod(SSLSocketFactory.class, "createKeyManagers", keystore, keystorePassword);
                            }

                            trustmanagers = new TrustManager[]{getTrustManager()};

                            setObjectField(param.thisObject, "sslcontext", SSLContext.getInstance(algorithm));
                            callMethod(getObjectField(param.thisObject, "sslcontext"), "init", keymanagers, trustmanagers, random);
                            setObjectField(param.thisObject, "socketfactory",
                                    callMethod(getObjectField(param.thisObject, "sslcontext"), "getSocketFactory"));
                        }

                    });


            /* external/apache-http/src/org/apache/http/conn/ssl/SSLSocketFactory.java */
            /* public static SSLSocketFactory getSocketFactory() */
            Log.d(TAG, "Hooking static SSLSocketFactory(String, KeyStore, String, KeyStore) for: " + currentPackageName);
            findAndHookMethod("org.apache.http.conn.ssl.SSLSocketFactory", lpparam.classLoader, "getSocketFactory", new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                    return (SSLSocketFactory) newInstance(SSLSocketFactory.class);
                }
            });
        } catch (NoClassDefFoundError e) {
            Log.d(TAG, "NoClassDefFoundError SSLSocketFactory HostNameResolver for: " + currentPackageName);
        }

        /* external/apache-http/src/org/apache/http/conn/ssl/SSLSocketFactory.java */
        /* public boolean isSecure(Socket) */
        Log.d(TAG, "Hooking SSLSocketFactory(Socket) for: " + currentPackageName);
        findAndHookMethod("org.apache.http.conn.ssl.SSLSocketFactory", lpparam.classLoader, "isSecure", Socket.class, DO_NOTHING);

        /* JSSE Hooks */
        /* libcore/luni/src/main/java/javax/net/ssl/TrustManagerFactory.java */
        /* public final TrustManager[] getTrustManager() */
        Log.d(TAG, "Hooking TrustManagerFactory.getTrustManagers() for: " + currentPackageName);
        findAndHookMethod("javax.net.ssl.TrustManagerFactory", lpparam.classLoader, "getTrustManagers", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {

                if (hasTrustManagerImpl()) {
                    Class<?> cls = findClass("com.android.org.conscrypt.TrustManagerImpl", lpparam.classLoader);

                    TrustManager[] managers = (TrustManager[]) param.getResult();
                    if (managers.length > 0 && cls.isInstance(managers[0]))
                        return;
                }

                param.setResult(new TrustManager[]{getTrustManager()});
            }
        });

        /* libcore/luni/src/main/java/javax/net/ssl/HttpsURLConnection.java */
        /* public void setDefaultHostnameVerifier(HostnameVerifier) */
        Log.d(TAG, "Hooking HttpsURLConnection.setDefaultHostnameVerifier for: " + currentPackageName);
        findAndHookMethod("javax.net.ssl.HttpsURLConnection", lpparam.classLoader, "setDefaultHostnameVerifier",
                HostnameVerifier.class, DO_NOTHING);

        /* libcore/luni/src/main/java/javax/net/ssl/HttpsURLConnection.java */
        /* public void setSSLSocketFactory(SSLSocketFactory) */
        Log.d(TAG, "Hooking HttpsURLConnection.setSSLSocketFactory for: " + currentPackageName);
        findAndHookMethod("javax.net.ssl.HttpsURLConnection", lpparam.classLoader, "setSSLSocketFactory", javax.net.ssl.SSLSocketFactory.class, DO_NOTHING);

        /* libcore/luni/src/main/java/javax/net/ssl/HttpsURLConnection.java */
        /* public void setHostnameVerifier(HostNameVerifier) */
        Log.d(TAG, "Hooking HttpsURLConnection.setHostnameVerifier for: " + currentPackageName);
        findAndHookMethod("javax.net.ssl.HttpsURLConnection", lpparam.classLoader, "setHostnameVerifier", HostnameVerifier.class, DO_NOTHING);


        /* WebView Hooks */
        /* frameworks/base/core/java/android/webkit/WebViewClient.java */
        /* public void onReceivedSslError(Webview, SslErrorHandler, SslError) */
        Log.d(TAG, "Hooking WebViewClient.onReceivedSslError(WebView, SslErrorHandler, SslError) for: " + currentPackageName);

        findAndHookMethod("android.webkit.WebViewClient", lpparam.classLoader, "onReceivedSslError",
                WebView.class, SslErrorHandler.class, SslError.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Log.d(TAG, "onReceivedSslError ignore:" + ((WebView)param.args[0]).getUrl());
                        ((SslErrorHandler) param.args[1]).proceed();
                        param.setResult(null);

                    }
                });

        /* frameworks/base/core/java/android/webkit/WebViewClient.java */
        /* public void onReceivedError(WebView, int, String, String) */
        Log.d(TAG, "Hooking WebViewClient.onReceivedError(WebView, int, string, string) for: " + currentPackageName);

        findAndHookMethod("android.webkit.WebViewClient", lpparam.classLoader, "onReceivedError",
                WebView.class, int.class, String.class, String.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Log.d(TAG, "onReceivedError ignore:" + ((WebView)param.args[0]).getUrl());
                        param.setResult(null);
                    }
                });

        //SSLContext.init >> (null,ImSureItsLegitTrustManager,null)
        findAndHookMethod("javax.net.ssl.SSLContext", lpparam.classLoader, "init", KeyManager[].class, TrustManager[].class, SecureRandom.class, new XC_MethodHook() {

            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {

                param.args[0] = null;
                param.args[1] = new TrustManager[]{getTrustManager()};
                param.args[2] = null;

            }
        });

        // Multi-dex support: https://github.com/rovo89/XposedBridge/issues/30#issuecomment-68486449
        findAndHookMethod("android.app.Application",
                lpparam.classLoader,
                "attach",
                Context.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        // Hook OkHttp or third party libraries.
                        Context context = (Context) param.args[0];
                        processOkHttp(context.getClassLoader());
                        processHttpClientAndroidLib(context.getClassLoader());
                        processXutils(context.getClassLoader());
                        processEnableDebug(context.getClassLoader());

                    }
                }
        );

        /* Only for newer devices should we try to hook TrustManagerImpl */
        if (hasTrustManagerImpl()) {
            /* TrustManagerImpl Hooks */
            /* external/conscrypt/src/platform/java/org/conscrypt/TrustManagerImpl.java */
            Log.d(TAG, "Hooking com.android.org.conscrypt.TrustManagerImpl for: " + currentPackageName);

            /* public void checkServerTrusted(X509Certificate[] chain, String authType) */
            findAndHookMethod("com.android.org.conscrypt.TrustManagerImpl", lpparam.classLoader,
                    "checkServerTrusted", X509Certificate[].class, String.class,
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            return 0;
                        }
                    });

            /* public List<X509Certificate> checkServerTrusted(X509Certificate[] chain,
                                    String authType, String host) throws CertificateException */
            findAndHookMethod("com.android.org.conscrypt.TrustManagerImpl", lpparam.classLoader,
                    "checkServerTrusted", X509Certificate[].class, String.class,
                    String.class, new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            ArrayList<X509Certificate> list = new ArrayList<X509Certificate>();
                            return list;
                        }
                    });

            /* public List<X509Certificate> checkServerTrusted(X509Certificate[] chain,
                                    String authType, SSLSession session) throws CertificateException */
            findAndHookMethod("com.android.org.conscrypt.TrustManagerImpl", lpparam.classLoader,
                    "checkServerTrusted", X509Certificate[].class, String.class,
                    SSLSession.class, new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            ArrayList<X509Certificate> list = new ArrayList<X509Certificate>();
                            return list;
                        }
                    });

            try {
                findAndHookMethod("com.android.org.conscrypt.TrustManagerImpl", lpparam.classLoader, "checkTrusted", X509Certificate[].class, String.class, SSLSession.class, SSLParameters.class, boolean.class, new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        ArrayList<X509Certificate> list = new ArrayList<X509Certificate>();
                        return list;
                    }
                });


                findAndHookMethod("com.android.org.conscrypt.TrustManagerImpl", lpparam.classLoader, "checkTrusted", X509Certificate[].class, byte[].class, byte[].class, String.class, String.class, boolean.class, new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        ArrayList<X509Certificate> list = new ArrayList<X509Certificate>();
                        return list;
                    }
                });
            } catch (NoSuchMethodError e) {

            }

        }

    } // End Hooks

    /* Helpers */
    // Check for TrustManagerImpl class
    public boolean hasTrustManagerImpl() {

        try {
            Class.forName("com.android.org.conscrypt.TrustManagerImpl");
        } catch (ClassNotFoundException e) {
            return false;
        }
        return true;
    }

    public boolean hasDefaultHTTPClient() {
        try {
            Class.forName("org.apache.http.impl.client.DefaultHttpClient");
        } catch (ClassNotFoundException e) {
            return false;
        }
        return true;
    }

    private javax.net.ssl.SSLSocketFactory getEmptySSLFactory() {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{getTrustManager()}, null);
            return sslContext.getSocketFactory();
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            return null;
        }
    }

    //Create a SingleClientConnManager that trusts everyone!
    public ClientConnectionManager getSCCM() {

        KeyStore trustStore;
        try {

            trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);

            SSLSocketFactory sf = new TrustAllSSLSocketFactory(trustStore);
            sf.setHostnameVerifier(SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);

            SchemeRegistry registry = new SchemeRegistry();
            registry.register(new Scheme("http", PlainSocketFactory.getSocketFactory(), 80));
            registry.register(new Scheme("https", sf, 443));

            ClientConnectionManager ccm = new SingleClientConnManager(null, registry);

            return ccm;

        } catch (Exception e) {
            return null;
        }
    }

    //This function creates a ThreadSafeClientConnManager that trusts everyone!
    public ClientConnectionManager getTSCCM(HttpParams params) {

        KeyStore trustStore;
        try {

            trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);

            SSLSocketFactory sf = new TrustAllSSLSocketFactory(trustStore);
            sf.setHostnameVerifier(SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);

            SchemeRegistry registry = new SchemeRegistry();
            registry.register(new Scheme("http", PlainSocketFactory.getSocketFactory(), 80));
            registry.register(new Scheme("https", sf, 443));

            ClientConnectionManager ccm = new ThreadSafeClientConnManager(params, registry);

            return ccm;

        } catch (Exception e) {
            return null;
        }
    }

    //This function determines what object we are dealing with.
    public ClientConnectionManager getCCM(Object o, HttpParams params) {

        String className = o.getClass().getSimpleName();

        if (className.equals("SingleClientConnManager")) {
            return getSCCM();
        } else if (className.equals("ThreadSafeClientConnManager")) {
            return getTSCCM(params);
        }

        return null;
    }

    private void processXutils(ClassLoader classLoader) {
        Log.d(TAG, "Hooking org.xutils.http.RequestParams.setSslSocketFactory(SSLSocketFactory) (3) for: " + currentPackageName);
        try {
            classLoader.loadClass("org.xutils.http.RequestParams");
            findAndHookMethod("org.xutils.http.RequestParams", classLoader, "setSslSocketFactory", javax.net.ssl.SSLSocketFactory.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    super.beforeHookedMethod(param);
                    param.args[0] = getEmptySSLFactory();
                }
            });
            findAndHookMethod("org.xutils.http.RequestParams", classLoader, "setHostnameVerifier", HostnameVerifier.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    super.beforeHookedMethod(param);
                    param.args[0] = new ImSureItsLegitHostnameVerifier();
                }
            });
        } catch (Exception e) {
            Log.d(TAG, "org.xutils.http.RequestParams not found in " + currentPackageName + "-- not hooking");
        }
    }

    void processOkHttp(ClassLoader classLoader) {
        /* hooking OKHTTP by SQUAREUP */
        /* com/squareup/okhttp/CertificatePinner.java available online @ https://github.com/square/okhttp/blob/master/okhttp/src/main/java/com/squareup/okhttp/CertificatePinner.java */
        /* public void check(String hostname, List<Certificate> peerCertificates) throws SSLPeerUnverifiedException{}*/
        /* Either returns true or a exception so blanket return true */
        /* Tested against version 2.5 */
        Log.d(TAG, "Hooking com.squareup.okhttp.CertificatePinner.check(String,List) (2.5) for: " + currentPackageName);

        try {
            classLoader.loadClass("com.squareup.okhttp.CertificatePinner");
            findAndHookMethod("com.squareup.okhttp.CertificatePinner",
                    classLoader,
                    "check",
                    String.class,
                    List.class,
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                            return true;
                        }
                    });
        } catch (ClassNotFoundException e) {
            // pass
            Log.d(TAG, "OKHTTP 2.5 not found in " + currentPackageName + "-- not hooking");
        }

        //https://github.com/square/okhttp/blob/parent-3.0.1/okhttp/src/main/java/okhttp3/CertificatePinner.java#L144
        Log.d(TAG, "Hooking okhttp3.CertificatePinner.check(String,List) (3.x) for: " + currentPackageName);

        try {
            classLoader.loadClass("okhttp3.CertificatePinner");
            findAndHookMethod("okhttp3.CertificatePinner",
                    classLoader,
                    "check",
                    String.class,
                    List.class,
                    DO_NOTHING);
        } catch (ClassNotFoundException e) {
            Log.d(TAG, "OKHTTP 3.x not found in " + currentPackageName + " -- not hooking");
            // pass
        }

        //https://github.com/square/okhttp/blob/parent-3.0.1/okhttp/src/main/java/okhttp3/internal/tls/OkHostnameVerifier.java
        try {
            classLoader.loadClass("okhttp3.internal.tls.OkHostnameVerifier");
            findAndHookMethod("okhttp3.internal.tls.OkHostnameVerifier",
                    classLoader,
                    "verify",
                    String.class,
                    javax.net.ssl.SSLSession.class,
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                            return true;
                        }
                    });
        } catch (ClassNotFoundException e) {
            Log.d(TAG, "OKHTTP 3.x not found in " + currentPackageName + " -- not hooking OkHostnameVerifier.verify(String, SSLSession)");
            // pass
        }

        //https://github.com/square/okhttp/blob/parent-3.0.1/okhttp/src/main/java/okhttp3/internal/tls/OkHostnameVerifier.java
        try {
            classLoader.loadClass("okhttp3.internal.tls.OkHostnameVerifier");
            findAndHookMethod("okhttp3.internal.tls.OkHostnameVerifier",
                    classLoader,
                    "verify",
                    String.class,
                    java.security.cert.X509Certificate.class,
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                            return true;
                        }
                    });
        } catch (ClassNotFoundException e) {
            Log.d(TAG, "OKHTTP 3.x not found in " + currentPackageName + " -- not hooking OkHostnameVerifier.verify(String, X509)(");
            // pass
        }

        //https://github.com/square/okhttp/blob/okhttp_4.2.x/okhttp/src/main/java/okhttp3/CertificatePinner.kt
        Log.d(TAG, "Hooking okhttp3.CertificatePinner.check(String,List) (4.2.0+) for: " + currentPackageName);

        try {
            classLoader.loadClass("okhttp3.CertificatePinner");
            findAndHookMethod("okhttp3.CertificatePinner",
                    classLoader,
                    "check$okhttp",
                    String.class,
                    "kotlin.jvm.functions.Function0",
                    DO_NOTHING);
        } catch (XposedHelpers.ClassNotFoundError | ClassNotFoundException | NoSuchMethodError e) {
            Log.d(TAG, "OKHTTP 4.2.0+ (check$okhttp) not found in " + currentPackageName + " -- not hooking");
            // pass
        }

        try {
            classLoader.loadClass("okhttp3.CertificatePinner");
            findAndHookMethod("okhttp3.CertificatePinner",
                    classLoader,
                    "check",
                    String.class,
                    List.class,
                    DO_NOTHING);
        } catch (XposedHelpers.ClassNotFoundError | ClassNotFoundException | NoSuchMethodError e) {
            Log.d(TAG, "OKHTTP 4.2.0+ (check) not found in " + currentPackageName + " -- not hooking");
            // pass
        }

        try {
            classLoader.loadClass("com.hn.library.okgocallback.JsonCallback");
            classLoader.loadClass("com.lzy.okgo.request.base.Request");
            Class requestClass = Class.forName("com.lzy.okgo.request.base.Request");
            findAndHookMethod("com.hn.library.okgocallback.JsonCallback",
                    classLoader,
                    "onStart",
                    requestClass,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                        }
                    });
        }catch (Exception e){

        }

    }

    void processHttpClientAndroidLib(ClassLoader classLoader) {
        /* httpclientandroidlib Hooks */
        /* public final void verify(String host, String[] cns, String[] subjectAlts, boolean strictWithSubDomains) throws SSLException */
        Log.d(TAG, "Hooking AbstractVerifier.verify(String, String[], String[], boolean) for: " + currentPackageName);

        try {
            classLoader.loadClass("ch.boye.httpclientandroidlib.conn.ssl.AbstractVerifier");
            findAndHookMethod("ch.boye.httpclientandroidlib.conn.ssl.AbstractVerifier", classLoader, "verify",
                    String.class, String[].class, String[].class, boolean.class,
                    DO_NOTHING);
        } catch (ClassNotFoundException e) {
            // pass
            Log.d(TAG, "httpclientandroidlib not found in " + currentPackageName + "-- not hooking");
        }
    }

    @TargetApi(Build.VERSION_CODES.N)
    private class ImSureItsLegitExtendedTrustManager extends X509ExtendedTrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {

        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {

        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {

        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {

        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {

        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {

        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }

        @SuppressLint("NewApi")
        public List<X509Certificate> checkServerTrusted(X509Certificate[] chain,
                                                        String authType,
                                                        String hostname,
                                                        String algorithm) {
            return Arrays.asList(chain); // 直接信任所有证书
        }
    }

    private class ImSureItsLegitTrustManager implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        }

        public void checkServerTrusted(X509Certificate[] chain, String authType, String host) throws CertificateException {
        }

        @SuppressLint("NewApi")
        public List<X509Certificate> checkServerTrusted(X509Certificate[] chain,
                                                        String authType,
                                                        String hostname,
                                                        String algorithm) {
            return Arrays.asList(chain); // 直接信任所有证书
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    private X509TrustManager getTrustManager() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return new ImSureItsLegitExtendedTrustManager();
        } else {
            return new ImSureItsLegitTrustManager();
        }
    }

    private class ImSureItsLegitHostnameVerifier implements HostnameVerifier {

        @Override
        public boolean verify(String hostname, SSLSession session) {
            return true;
        }
    }

    /* This class creates a SSLSocket that trusts everyone. */
    public class TrustAllSSLSocketFactory extends SSLSocketFactory {

        SSLContext sslContext = SSLContext.getInstance("TLS");

        public TrustAllSSLSocketFactory(KeyStore truststore) throws
                NoSuchAlgorithmException, KeyManagementException, KeyStoreException, UnrecoverableKeyException {
            super(truststore);

            TrustManager tm = new X509TrustManager() {

                public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                }

                public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                }

                public X509Certificate[] getAcceptedIssuers() {
                    return null;
                }
            };

            sslContext.init(null, new TrustManager[]{tm}, null);
        }

        @Override
        public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException, UnknownHostException {
            Log.d(TAG, "Hooking createSocket for: " + host + ":" + port);

            return sslContext.getSocketFactory().createSocket(socket, host, port, autoClose);
        }

        @Override
        public Socket createSocket() throws IOException {
            return sslContext.getSocketFactory().createSocket();
        }
    }

    public void processEnableDebug(ClassLoader classLoader){
        //com.yuyin.live.biz.HnNuggetBiz#getNuggetRewardLog
        try {
            classLoader.loadClass("com.kiwi.sdk.Kiwi");
//            findAndHookMethod("com.kiwi.sdk.Kiwi", classLoader, "Init", String.class, new XC_MethodReplacement() {
//                @Override
//                protected Object replaceHookedMethod(MethodHookParam methodHookParam) throws Throwable {
//                    Log.i(TAG, "Hooking Kiwi.Init");
//                    return -1;
//                }
//            });

            findAndHookMethod("com.kiwi.sdk.Kiwi", classLoader, "server_to_local", String.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                    Log.i(TAG, "server_to_local:" + methodHookParam.getResult());
                }
            });


        } catch (Exception e) {

        }

        try {
            classLoader.loadClass("imechos.com.base.utils.PackageUtil");
            findAndHookMethod("imechos.com.base.utils.PackageUtil", classLoader, "hasProxy",Context.class, new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                    Log.i(TAG, "PackageUtil#hasProxy");
                    return false;
                }
            });
        } catch (Exception e) {

        }

        try {
            classLoader.loadClass("imechos.com.base.utils.VpnUtils");
            findAndHookMethod("imechos.com.base.utils.VpnUtils", classLoader, "hasProxy",Context.class, new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                    Log.i(TAG, "VpnUtils#hasProxy");
                    return false;
                }
            });
        } catch (Exception e) {

        }

        try {
            Log.i(TAG, " hooking dev.fluttercommunity.plus.connectivity.OooO00o#OooO0O0");
            Class clazz = classLoader.loadClass("dev.fluttercommunity.plus.connectivity.OooO00o");

            findAndHookMethod("dev.fluttercommunity.plus.connectivity.OooO00o", classLoader, "OooO0O0", new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                    Log.i(TAG, "PackageUtil#hasProxy");
                    return "wifi";
                }
            });




        } catch (Exception e) {
            Log.e(TAG, "RongChatRoomClientImpl#joinChatRoom error", e);
        }

    }
}
