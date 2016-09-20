/*
 * Copyright (C) 2012 The Android Open Source Project
 *
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
 */

package com.android.server.webkit;

import android.app.ActivityManagerNative;
import android.app.AppGlobals;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageDeleteObserver;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.UserInfo;
import android.os.Binder;
import android.os.PatternMatcher;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.util.AndroidRuntimeException;
import android.util.Slog;
import android.webkit.IWebViewUpdateService;
import android.webkit.WebViewProviderInfo;
import android.webkit.WebViewProviderResponse;
import android.webkit.WebViewFactory;

import com.android.server.SystemService;

import java.io.FileDescriptor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * Private service to wait for the updatable WebView to be ready for use.
 * @hide
 */
public class WebViewUpdateService extends SystemService {

    private static final String TAG = "WebViewUpdateService";
    private static final int WAIT_TIMEOUT_MS = 4500; // KEY_DISPATCHING_TIMEOUT is 5000.

    // Keeps track of the number of running relro creations
    private int mNumRelroCreationsStarted = 0;
    private int mNumRelroCreationsFinished = 0;
    // Implies that we need to rerun relro creation because we are using an out-of-date package
    private boolean mWebViewPackageDirty = false;
    // Set to true when the current provider is being replaced
    private boolean mCurrentProviderBeingReplaced = false;
    private boolean mAnyWebViewInstalled = false;

    private int NUMBER_OF_RELROS_UNKNOWN = Integer.MAX_VALUE;

    // The WebView package currently in use (or the one we are preparing).
    private PackageInfo mCurrentWebViewPackage = null;
    // The WebView providers that are currently available.
    private WebViewProviderInfo[] mCurrentValidWebViewPackages = null;

    private BroadcastReceiver mWebViewUpdatedReceiver;

    public WebViewUpdateService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        mWebViewUpdatedReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    // When a package is replaced we will receive two intents, one representing
                    // the removal of the old package and one representing the addition of the
                    // new package.
                    // In the case where we receive an intent to remove the old version of the
                    // package that is being replaced we set a flag here and early-out so that we
                    // don't change provider while replacing the current package (we will instead
                    // change provider when the new version of the package is being installed).
                    if (intent.getAction().equals(Intent.ACTION_PACKAGE_REMOVED)
                        && intent.getExtras().getBoolean(Intent.EXTRA_REPLACING)) {
                        synchronized(WebViewUpdateService.this) {
                            if (mCurrentWebViewPackage == null) return;

                            String webViewPackage = "package:" + mCurrentWebViewPackage.packageName;
                            if (webViewPackage.equals(intent.getDataString()))
                                mCurrentProviderBeingReplaced = true;
                        }

                        return;
                    }

                    // Ensure that we only heed PACKAGE_CHANGED intents if they change an entire
                    // package, not just a component
                    if (intent.getAction().equals(Intent.ACTION_PACKAGE_CHANGED)) {
                        if (!WebViewFactory.entirePackageChanged(intent)) {
                            return;
                        }
                    }

                    if (intent.getAction().equals(Intent.ACTION_USER_ADDED)) {
                        int userId =
                            intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);
                        handleNewUser(userId);
                        return;
                    }

                    updateFallbackState(context, intent);

                    // TODO(gsennton) for now don't update WebView on PACKAGE_CHANGED as this will
                    // change the current behaviour even more, instead do this in a follow-up.
                    if (intent.getAction().equals(Intent.ACTION_PACKAGE_CHANGED)) return;

                    for (WebViewProviderInfo provider : WebViewFactory.getWebViewPackages()) {
                        String webviewPackage = "package:" + provider.packageName;

                        if (webviewPackage.equals(intent.getDataString())) {
                            boolean updateWebView = false;
                            boolean removedOldPackage = false;
                            String oldProviderName = null;
                            PackageInfo newPackage = null;
                            synchronized(WebViewUpdateService.this) {
                                try {
                                    updateValidWebViewPackages();
                                    newPackage = findPreferredWebViewPackage();
                                    if (mCurrentWebViewPackage != null)
                                        oldProviderName = mCurrentWebViewPackage.packageName;
                                    // Only trigger update actions if the updated package is the one
                                    // that will be used, or the one that was in use before the
                                    // update, or if we haven't seen a valid WebView package before.
                                    updateWebView =
                                        provider.packageName.equals(newPackage.packageName)
                                        || provider.packageName.equals(oldProviderName)
                                        || mCurrentWebViewPackage == null;
                                    // We removed the old package if we received an intent to remove
                                    // or replace the old package.
                                    removedOldPackage =
                                        provider.packageName.equals(oldProviderName);
                                    if (updateWebView) {
                                        onWebViewProviderChanged(newPackage);
                                    }
                                } catch (WebViewFactory.MissingWebViewPackageException e) {
                                    Slog.e(TAG, "Could not find valid WebView package to create " +
                                            "relro with " + e);
                                }
                            }
                            if(updateWebView && !removedOldPackage && oldProviderName != null) {
                                // If the provider change is the result of adding or replacing a
                                // package that was not the previous provider then we must kill
                                // packages dependent on the old package ourselves. The framework
                                // only kills dependents of packages that are being removed.
                                try {
                                    ActivityManagerNative.getDefault().killPackageDependents(
                                        oldProviderName, UserHandle.USER_ALL);
                                } catch (RemoteException e) {
                                }
                            }
                            return;
                        }
                    }
                }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        // Make sure we only receive intents for WebView packages from our config file.
        for (WebViewProviderInfo provider : WebViewFactory.getWebViewPackages()) {
            filter.addDataSchemeSpecificPart(provider.packageName, PatternMatcher.PATTERN_LITERAL);
        }
        getContext().registerReceiver(mWebViewUpdatedReceiver, filter);

        IntentFilter userAddedFilter = new IntentFilter();
        userAddedFilter.addAction(Intent.ACTION_USER_ADDED);
        getContext().registerReceiver(mWebViewUpdatedReceiver, userAddedFilter);

        publishBinderService("webviewupdate", new BinderService());
    }

    private static boolean existsValidNonFallbackProvider(WebViewProviderInfo[] providers) {
        for (WebViewProviderInfo provider : providers) {
            if (provider.isAvailableByDefault() && provider.isEnabled()
                    && provider.isValidProvider() && !provider.isFallbackPackage()) {
                return true;
            }
        }
        return false;
    }

    private static void enablePackageForUser(String packageName, boolean enable, int userId) {
        try {
            AppGlobals.getPackageManager().setApplicationEnabledSetting(
                    packageName,
                    enable ? PackageManager.COMPONENT_ENABLED_STATE_DEFAULT :
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, 0,
                    userId, null);
        } catch (RemoteException e) {
            Slog.w(TAG, "Tried to disable " + packageName + " for user " + userId + ": " + e);
        }
    }

    /**
     * Called when a new user has been added to update the state of its fallback package.
     */
    void handleNewUser(int userId) {
        if (!isFallbackLogicEnabled()) return;

        WebViewProviderInfo[] webviewProviders = WebViewFactory.getWebViewPackages();
        WebViewProviderInfo fallbackProvider = getFallbackProvider(webviewProviders);
        if (fallbackProvider == null) return;
        boolean existsValidNonFallbackProvider =
            existsValidNonFallbackProvider(webviewProviders);

        enablePackageForUser(fallbackProvider.packageName, !existsValidNonFallbackProvider,
                userId);
    }

    /**
     * Handle the enabled-state of our fallback package, i.e. if there exists some non-fallback
     * package that is valid (and available by default) then disable the fallback package,
     * otherwise, enable the fallback package.
     */
    void updateFallbackState(final Context context, final Intent intent) {
        if (!isFallbackLogicEnabled()) return;

        WebViewProviderInfo[] webviewProviders = WebViewFactory.getWebViewPackages();

        if (intent != null && (intent.getAction().equals(Intent.ACTION_PACKAGE_ADDED)
                    || intent.getAction().equals(Intent.ACTION_PACKAGE_CHANGED))) {
            // A package was changed / updated / downgraded, early out if it is not one of the
            // webview packages that are available by default.
            String changedPackage = null;
            for (WebViewProviderInfo provider : webviewProviders) {
                String webviewPackage = "package:" + provider.packageName;
                if (webviewPackage.equals(intent.getDataString())) {
                    if (provider.isAvailableByDefault()) {
                        changedPackage = provider.packageName;
                    }
                    break;
                }
            }
            if (changedPackage == null) return;
        }

        // If there exists a valid and enabled non-fallback package - disable the fallback
        // package, otherwise, enable it.
        WebViewProviderInfo fallbackProvider = getFallbackProvider(webviewProviders);
        if (fallbackProvider == null) return;
        boolean existsValidNonFallbackProvider = existsValidNonFallbackProvider(webviewProviders);

        if (existsValidNonFallbackProvider
                // During an OTA the primary user's WebView state might differ from other users', so
                // ignore the state of that user during boot.
                && (fallbackProvider.isEnabled() || intent == null)) {
            // Uninstall and disable fallback package for all users.
            context.getPackageManager().deletePackage(fallbackProvider.packageName,
                    new IPackageDeleteObserver.Stub() {
                public void packageDeleted(String packageName, int returnCode) {
                    // Ignore returnCode since the deletion could fail, e.g. we might be trying
                    // to delete a non-updated system-package (and we should still disable the
                    // package)
                    UserManager userManager =
                        (UserManager)context.getSystemService(Context.USER_SERVICE);
                    // Disable the fallback package for all users.
                    for(UserInfo userInfo : userManager.getUsers()) {
                        enablePackageForUser(packageName, false, userInfo.id);
                    }
                }
            }, PackageManager.DELETE_SYSTEM_APP | PackageManager.DELETE_ALL_USERS);
        } else if (!existsValidNonFallbackProvider
                // During an OTA the primary user's WebView state might differ from other users', so
                // ignore the state of that user during boot.
                && (!fallbackProvider.isEnabled() || intent==null)) {
            // Enable the fallback package for all users.
            UserManager userManager =
                (UserManager)context.getSystemService(Context.USER_SERVICE);
            for(UserInfo userInfo : userManager.getUsers()) {
                enablePackageForUser(fallbackProvider.packageName, true, userInfo.id);
            }
        }
    }

    private static boolean isFallbackLogicEnabled() {
        // Note that this is enabled by default (i.e. if the setting hasn't been set).
        return Settings.Global.getInt(AppGlobals.getInitialApplication().getContentResolver(),
                Settings.Global.WEBVIEW_FALLBACK_LOGIC_ENABLED, 1) == 1;
    }

    private static void enableFallbackLogic(boolean enable) {
        Settings.Global.putInt(AppGlobals.getInitialApplication().getContentResolver(),
                Settings.Global.WEBVIEW_FALLBACK_LOGIC_ENABLED, enable ? 1 : 0);
    }

    /**
     * Returns the only fallback provider, or null if there is none.
     */
    private static WebViewProviderInfo getFallbackProvider(WebViewProviderInfo[] webviewPackages) {
        for (WebViewProviderInfo provider : webviewPackages) {
            if (provider.isFallbackPackage()) {
                return provider;
            }
        }
        return null;
    }

    private static boolean containsAvailableNonFallbackProvider(
            WebViewProviderInfo[] webviewPackages) {
        for (WebViewProviderInfo provider : webviewPackages) {
            if (provider.isAvailableByDefault() && provider.isEnabled()
                    && provider.isValidProvider() && !provider.isFallbackPackage()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFallbackPackage(String packageName) {
        if (packageName == null || !isFallbackLogicEnabled()) return false;

        WebViewProviderInfo[] webviewPackages = WebViewFactory.getWebViewPackages();
        WebViewProviderInfo fallbackProvider = getFallbackProvider(webviewPackages);
        return (fallbackProvider != null
                && packageName.equals(fallbackProvider.packageName));
    }

    /**
     * Perform any WebView loading preparations that must happen at boot from the system server,
     * after the package manager has started or after an update to the webview is installed.
     * This must be called in the system server.
     * Currently, this means spawning the child processes which will create the relro files.
     */
    public void prepareWebViewInSystemServer() {
        updateFallbackState(getContext(), null);
        try {
            synchronized(this) {
                updateValidWebViewPackages();
                mCurrentWebViewPackage = findPreferredWebViewPackage();
                onWebViewProviderChanged(mCurrentWebViewPackage);
            }
        } catch (Throwable t) {
            // Log and discard errors at this stage as we must not crash the system server.
            Slog.e(TAG, "error preparing webview provider from system server", t);
        }
    }


    /**
     * Change WebView provider and provider setting and kill packages using the old provider.
     * Return the new provider (in case we are in the middle of creating relro files this new
     * provider will not be in use directly, but will when the relros are done).
     */
    private String changeProviderAndSetting(String newProviderName) {
        PackageInfo oldPackage = null;
        PackageInfo newPackage = null;
        synchronized(this) {
            oldPackage = mCurrentWebViewPackage;
            updateUserSetting(newProviderName);

            try {
                newPackage = findPreferredWebViewPackage();
                if (oldPackage != null && newPackage.packageName.equals(oldPackage.packageName)) {
                    // If we don't perform the user change, revert the settings change.
                    updateUserSetting(newPackage.packageName);
                    return newPackage.packageName;
                }
            } catch (WebViewFactory.MissingWebViewPackageException e) {
                Slog.e(TAG, "Tried to change WebView provider but failed to fetch WebView package "
                        + e);
                // If we don't perform the user change but don't have an installed WebView package,
                // we will have changed the setting and it will be used when a package is available.
                return newProviderName;
            }
            onWebViewProviderChanged(newPackage);
        }
        // Kill apps using the old provider
        try {
            if (oldPackage != null) {
                ActivityManagerNative.getDefault().killPackageDependents(
                        oldPackage.packageName, UserHandle.USER_ALL);
            }
        } catch (RemoteException e) {
        }
        return newPackage.packageName;
    }

    /**
     * This is called when we change WebView provider, either when the current provider is updated
     * or a new provider is chosen / takes precedence.
     */
    private void onWebViewProviderChanged(PackageInfo newPackage) {
        synchronized(this) {
            mAnyWebViewInstalled = true;
            // If we have changed provider then the replacement of the old provider is
            // irrelevant - we can only have chosen a new provider if its package is available.
            mCurrentProviderBeingReplaced = false;
            if (mNumRelroCreationsStarted == mNumRelroCreationsFinished) {
                mCurrentWebViewPackage = newPackage;
                updateUserSetting(newPackage.packageName);

                // The relro creations might 'finish' (not start at all) before
                // WebViewFactory.onWebViewProviderChanged which means we might not know the number
                // of started creations before they finish.
                mNumRelroCreationsStarted = NUMBER_OF_RELROS_UNKNOWN;
                mNumRelroCreationsFinished = 0;
                mNumRelroCreationsStarted = WebViewFactory.onWebViewProviderChanged(newPackage);
                // If the relro creations finish before we know the number of started creations we
                // will have to do any cleanup/notifying here.
                checkIfRelrosDoneLocked();
            } else {
                mWebViewPackageDirty = true;
            }
        }
    }

    /**
     * Updates the currently valid WebView provider packages.
     * Should be used when a provider has been installed or removed.
     * @hide
     * */
    private void updateValidWebViewPackages() {
        List<WebViewProviderInfo> webViewProviders  =
            new ArrayList<WebViewProviderInfo>(Arrays.asList(WebViewFactory.getWebViewPackages()));
        Iterator<WebViewProviderInfo> it = webViewProviders.iterator();
        // remove non-valid packages
        while(it.hasNext()) {
            WebViewProviderInfo current = it.next();
            if (!current.isValidProvider())
                it.remove();
        }
        synchronized(this) {
            mCurrentValidWebViewPackages =
                webViewProviders.toArray(new WebViewProviderInfo[webViewProviders.size()]);
        }
    }

    private static String getUserChosenWebViewProvider() {
        return Settings.Global.getString(AppGlobals.getInitialApplication().getContentResolver(),
                Settings.Global.WEBVIEW_PROVIDER);
    }

    private void updateUserSetting(String newProviderName) {
        Settings.Global.putString(getContext().getContentResolver(),
                Settings.Global.WEBVIEW_PROVIDER,
                newProviderName == null ? "" : newProviderName);
    }

    /**
     * Returns either the package info of the WebView provider determined in the following way:
     * If the user has chosen a provider then use that if it is valid,
     * otherwise use the first package in the webview priority list that is valid.
     *
     * @hide
     */
    private PackageInfo findPreferredWebViewPackage() {
        WebViewProviderInfo[] providers = mCurrentValidWebViewPackages;

        String userChosenProvider = getUserChosenWebViewProvider();

        // If the user has chosen provider, use that
        for (WebViewProviderInfo provider : providers) {
            if (provider.packageName.equals(userChosenProvider) && provider.isEnabled()) {
                return provider.getPackageInfo();
            }
        }

        // User did not choose, or the choice failed; use the most stable provider that is
        // enabled and available by default (not through user choice).
        for (WebViewProviderInfo provider : providers) {
            if (provider.isAvailableByDefault() && provider.isEnabled()) {
                return provider.getPackageInfo();
            }
        }

        // Could not find any enabled package either, use the most stable provider.
        for (WebViewProviderInfo provider : providers) {
            return provider.getPackageInfo();
        }

        mAnyWebViewInstalled = false;
        throw new WebViewFactory.MissingWebViewPackageException(
                "Could not find a loadable WebView package");
    }

    /**
     * Returns whether WebView is ready and is not going to go through its preparation phase again
     * directly.
     */
    private boolean webViewIsReadyLocked() {
        return !mWebViewPackageDirty
            && (mNumRelroCreationsStarted == mNumRelroCreationsFinished)
            && !mCurrentProviderBeingReplaced
            // The current package might be replaced though we haven't received an intent declaring
            // this yet, the following flag makes anyone loading WebView to wait in this case.
            && mAnyWebViewInstalled;
    }

    private void checkIfRelrosDoneLocked() {
        if (mNumRelroCreationsStarted == mNumRelroCreationsFinished) {
            if (mWebViewPackageDirty) {
                mWebViewPackageDirty = false;
                // If we have changed provider since we started the relro creation we need to
                // redo the whole process using the new package instead.
                // Though, if the current provider package is being replaced we don't want to change
                // provider here since we will perform the change either when the package is added
                // again or when we switch to another provider (whichever comes first).
                if (!mCurrentProviderBeingReplaced) {
                    PackageInfo newPackage = findPreferredWebViewPackage();
                    onWebViewProviderChanged(newPackage);
                }
            } else {
                this.notifyAll();
            }
        }
    }

    private class BinderService extends IWebViewUpdateService.Stub {

        @Override
        public void onShellCommand(FileDescriptor in, FileDescriptor out,
                FileDescriptor err, String[] args, ResultReceiver resultReceiver) {
            (new WebViewUpdateServiceShellCommand(this)).exec(
                    this, in, out, err, args, resultReceiver);
        }


        /**
         * The shared relro process calls this to notify us that it's done trying to create a relro
         * file. This method gets called even if the relro creation has failed or the process
         * crashed.
         */
        @Override // Binder call
        public void notifyRelroCreationCompleted() {
            // Verify that the caller is either the shared relro process (nominal case) or the
            // system server (only in the case the relro process crashes and we get here via the
            // crashHandler).
            if (Binder.getCallingUid() != Process.SHARED_RELRO_UID &&
                    Binder.getCallingUid() != Process.SYSTEM_UID) {
                return;
            }

            long callingId = Binder.clearCallingIdentity();
            try {
                synchronized (WebViewUpdateService.this) {
                    mNumRelroCreationsFinished++;
                    checkIfRelrosDoneLocked();
                }
            } finally {
                Binder.restoreCallingIdentity(callingId);
            }
        }

        /**
         * WebViewFactory calls this to block WebView loading until the relro file is created.
         * Returns the WebView provider for which we create relro files.
         */
        @Override // Binder call
        public WebViewProviderResponse waitForAndGetProvider() {
            // The WebViewUpdateService depends on the prepareWebViewInSystemServer call, which
            // happens later (during the PHASE_ACTIVITY_MANAGER_READY) in SystemServer.java. If
            // another service there tries to bring up a WebView in the between, the wait below
            // would deadlock without the check below.
            if (Binder.getCallingPid() == Process.myPid()) {
                throw new IllegalStateException("Cannot create a WebView from the SystemServer");
            }

            PackageInfo webViewPackage = null;
            final long NS_PER_MS = 1000000;
            final long timeoutTimeMs = System.nanoTime() / NS_PER_MS + WAIT_TIMEOUT_MS;
            boolean webViewReady = false;
            int webViewStatus = WebViewFactory.LIBLOAD_SUCCESS;
            synchronized (WebViewUpdateService.this) {
                webViewReady = WebViewUpdateService.this.webViewIsReadyLocked();
                while (!webViewReady) {
                    final long timeNowMs = System.nanoTime() / NS_PER_MS;
                    if (timeNowMs >= timeoutTimeMs) break;
                    try {
                        WebViewUpdateService.this.wait(timeoutTimeMs - timeNowMs);
                    } catch (InterruptedException e) {}
                    webViewReady = WebViewUpdateService.this.webViewIsReadyLocked();
                }
                // Make sure we return the provider that was used to create the relro file
                webViewPackage = WebViewUpdateService.this.mCurrentWebViewPackage;
                if (webViewReady) {
                } else if (mCurrentProviderBeingReplaced) {
                    // It is important that we check this flag before the one representing WebView
                    // being installed, otherwise we might think there is no WebView though the
                    // current one is just being replaced.
                    webViewStatus = WebViewFactory.LIBLOAD_WEBVIEW_BEING_REPLACED;
                } else if (!mAnyWebViewInstalled) {
                    webViewStatus = WebViewFactory.LIBLOAD_FAILED_LISTING_WEBVIEW_PACKAGES;
                } else {
                    // Either the current relro creation  isn't done yet, or the new relro creatioin
                    // hasn't kicked off yet (the last relro creation used an out-of-date WebView).
                    webViewStatus = WebViewFactory.LIBLOAD_FAILED_WAITING_FOR_RELRO;
                }
            }
            if (!webViewReady) Slog.w(TAG, "creating relro file timed out");
            return new WebViewProviderResponse(webViewPackage, webViewStatus);
        }

        /**
         * This is called from DeveloperSettings when the user changes WebView provider.
         */
        @Override // Binder call
        public String changeProviderAndSetting(String newProvider) {
            if (getContext().checkCallingPermission(
                        android.Manifest.permission.WRITE_SECURE_SETTINGS)
                    != PackageManager.PERMISSION_GRANTED) {
                String msg = "Permission Denial: changeProviderAndSetting() from pid="
                        + Binder.getCallingPid()
                        + ", uid=" + Binder.getCallingUid()
                        + " requires " + android.Manifest.permission.WRITE_SECURE_SETTINGS;
                Slog.w(TAG, msg);
                throw new SecurityException(msg);
            }

            return WebViewUpdateService.this.changeProviderAndSetting(newProvider);
        }

        @Override // Binder call
        public WebViewProviderInfo[] getValidWebViewPackages() {
            synchronized(WebViewUpdateService.this) {
                return mCurrentValidWebViewPackages;
            }
        }

        @Override // Binder call
        public String getCurrentWebViewPackageName() {
            synchronized(WebViewUpdateService.this) {
                if (WebViewUpdateService.this.mCurrentWebViewPackage == null)
                    return null;
                return WebViewUpdateService.this.mCurrentWebViewPackage.packageName;
            }
        }

        @Override // Binder call
        public boolean isFallbackPackage(String packageName) {
            return WebViewUpdateService.isFallbackPackage(packageName);
        }

        @Override // Binder call
        public void enableFallbackLogic(boolean enable) {
            if (getContext().checkCallingPermission(
                        android.Manifest.permission.WRITE_SECURE_SETTINGS)
                    != PackageManager.PERMISSION_GRANTED) {
                String msg = "Permission Denial: enableFallbackLogic() from pid="
                        + Binder.getCallingPid()
                        + ", uid=" + Binder.getCallingUid()
                        + " requires " + android.Manifest.permission.WRITE_SECURE_SETTINGS;
                Slog.w(TAG, msg);
                throw new SecurityException(msg);
            }

            WebViewUpdateService.enableFallbackLogic(enable);
        }
    }
}
