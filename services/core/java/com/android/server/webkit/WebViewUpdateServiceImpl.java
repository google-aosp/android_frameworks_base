/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.Signature;
import android.util.Base64;
import android.util.Slog;
import android.webkit.WebViewFactory;
import android.webkit.WebViewProviderInfo;
import android.webkit.WebViewProviderResponse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Implementation of the WebViewUpdateService.
 * This class doesn't depend on the android system like the actual Service does and can be used
 * directly by tests (as long as they implement a SystemInterface).
 * @hide
 */
public class WebViewUpdateServiceImpl {
    private static final String TAG = WebViewUpdateServiceImpl.class.getSimpleName();

    private SystemInterface mSystemInterface;
    private WebViewUpdater mWebViewUpdater;
    private Context mContext;

    public WebViewUpdateServiceImpl(Context context, SystemInterface systemInterface) {
        mContext = context;
        mSystemInterface = systemInterface;
        mWebViewUpdater = new WebViewUpdater(mContext, mSystemInterface);
    }

    void packageStateChanged(String packageName, int changedState) {
        updateFallbackStateOnPackageChange(packageName, changedState);
        mWebViewUpdater.packageStateChanged(packageName, changedState);
    }

    void prepareWebViewInSystemServer() {
        updateFallbackStateOnBoot();
        mWebViewUpdater.prepareWebViewInSystemServer();
    }

    private boolean existsValidNonFallbackProvider(WebViewProviderInfo[] providers) {
        for (WebViewProviderInfo provider : providers) {
            if (provider.availableByDefault && !provider.isFallback) {
                try {
                    PackageInfo packageInfo = mSystemInterface.getPackageInfoForProvider(provider);
                    if (isEnabledPackage(packageInfo)
                            && mWebViewUpdater.isValidProvider(provider, packageInfo)) {
                        return true;
                    }
                } catch (NameNotFoundException e) {
                    // A non-existent provider is neither valid nor enabled
                }
            }
        }
        return false;
    }

    /**
     * Called when a new user has been added to update the state of its fallback package.
     */
    void handleNewUser(int userId) {
        if (!mSystemInterface.isFallbackLogicEnabled()) return;

        WebViewProviderInfo[] webviewProviders = mSystemInterface.getWebViewPackages();
        WebViewProviderInfo fallbackProvider = getFallbackProvider(webviewProviders);
        if (fallbackProvider == null) return;

        mSystemInterface.enablePackageForUser(fallbackProvider.packageName,
                !existsValidNonFallbackProvider(webviewProviders), userId);
    }

    void notifyRelroCreationCompleted() {
        mWebViewUpdater.notifyRelroCreationCompleted();
    }

    WebViewProviderResponse waitForAndGetProvider() {
        return mWebViewUpdater.waitForAndGetProvider();
    }

    String changeProviderAndSetting(String newProvider) {
        return mWebViewUpdater.changeProviderAndSetting(newProvider);
    }

    WebViewProviderInfo[] getValidWebViewPackages() {
        return mWebViewUpdater.getValidWebViewPackages();
    }

    WebViewProviderInfo[] getWebViewPackages() {
        return mSystemInterface.getWebViewPackages();
    }

    String getCurrentWebViewPackageName() {
        return mWebViewUpdater.getCurrentWebViewPackageName();
    }

    void enableFallbackLogic(boolean enable) {
        mSystemInterface.enableFallbackLogic(enable);
    }

    private void updateFallbackStateOnBoot() {
        if (!mSystemInterface.isFallbackLogicEnabled()) return;

        WebViewProviderInfo[] webviewProviders = mSystemInterface.getWebViewPackages();
        updateFallbackState(webviewProviders, true);
    }

    /**
     * Handle the enabled-state of our fallback package, i.e. if there exists some non-fallback
     * package that is valid (and available by default) then disable the fallback package,
     * otherwise, enable the fallback package.
     */
    private void updateFallbackStateOnPackageChange(String changedPackage, int changedState) {
        if (!mSystemInterface.isFallbackLogicEnabled()) return;

        WebViewProviderInfo[] webviewProviders = mSystemInterface.getWebViewPackages();

        // A package was changed / updated / downgraded, early out if it is not one of the
        // webview packages that are available by default.
        boolean changedPackageAvailableByDefault = false;
        for (WebViewProviderInfo provider : webviewProviders) {
            if (provider.packageName.equals(changedPackage)) {
                if (provider.availableByDefault) {
                    changedPackageAvailableByDefault = true;
                }
                break;
            }
        }
        if (!changedPackageAvailableByDefault) return;
        updateFallbackState(webviewProviders, false);
    }

    private void updateFallbackState(WebViewProviderInfo[] webviewProviders, boolean isBoot) {
        // If there exists a valid and enabled non-fallback package - disable the fallback
        // package, otherwise, enable it.
        WebViewProviderInfo fallbackProvider = getFallbackProvider(webviewProviders);
        if (fallbackProvider == null) return;
        boolean existsValidNonFallbackProvider = existsValidNonFallbackProvider(webviewProviders);

        boolean isFallbackEnabled = false;
        try {
            isFallbackEnabled = isEnabledPackage(
                    mSystemInterface.getPackageInfoForProvider(fallbackProvider));
        } catch (NameNotFoundException e) {
            // No fallback package installed -> early out.
            return;
        }

        if (existsValidNonFallbackProvider
                // During an OTA the primary user's WebView state might differ from other users', so
                // ignore the state of that user during boot.
                && (isFallbackEnabled || isBoot)) {
            mSystemInterface.uninstallAndDisablePackageForAllUsers(mContext,
                    fallbackProvider.packageName);
        } else if (!existsValidNonFallbackProvider
                // During an OTA the primary user's WebView state might differ from other users', so
                // ignore the state of that user during boot.
                && (!isFallbackEnabled || isBoot)) {
            // Enable the fallback package for all users.
            mSystemInterface.enablePackageForAllUsers(mContext,
                    fallbackProvider.packageName, true);
        }
    }

    /**
     * Returns the only fallback provider in the set of given packages, or null if there is none.
     */
    private static WebViewProviderInfo getFallbackProvider(WebViewProviderInfo[] webviewPackages) {
        for (WebViewProviderInfo provider : webviewPackages) {
            if (provider.isFallback) {
                return provider;
            }
        }
        return null;
    }

    boolean isFallbackPackage(String packageName) {
        if (packageName == null || !mSystemInterface.isFallbackLogicEnabled()) return false;

        WebViewProviderInfo[] webviewPackages = mSystemInterface.getWebViewPackages();
        WebViewProviderInfo fallbackProvider = getFallbackProvider(webviewPackages);
        return (fallbackProvider != null
                && packageName.equals(fallbackProvider.packageName));
    }

    /**
     * Class that decides what WebView implementation to use and prepares that implementation for
     * use.
     */
    private static class WebViewUpdater {
        private Context mContext;
        private SystemInterface mSystemInterface;
        private int mMinimumVersionCode = -1;

        public WebViewUpdater(Context context, SystemInterface systemInterface) {
            mContext = context;
            mSystemInterface = systemInterface;
        }

        private static final int WAIT_TIMEOUT_MS = 4500; // KEY_DISPATCHING_TIMEOUT is 5000.

        // Keeps track of the number of running relro creations
        private int mNumRelroCreationsStarted = 0;
        private int mNumRelroCreationsFinished = 0;
        // Implies that we need to rerun relro creation because we are using an out-of-date package
        private boolean mWebViewPackageDirty = false;
        private boolean mAnyWebViewInstalled = false;

        private int NUMBER_OF_RELROS_UNKNOWN = Integer.MAX_VALUE;

        // The WebView package currently in use (or the one we are preparing).
        private PackageInfo mCurrentWebViewPackage = null;

        private Object mLock = new Object();

        public void packageStateChanged(String packageName, int changedState) {
            for (WebViewProviderInfo provider : mSystemInterface.getWebViewPackages()) {
                String webviewPackage = provider.packageName;

                if (webviewPackage.equals(packageName)) {
                    boolean updateWebView = false;
                    boolean removedOrChangedOldPackage = false;
                    String oldProviderName = null;
                    PackageInfo newPackage = null;
                    synchronized(mLock) {
                        try {
                            newPackage = findPreferredWebViewPackage();
                            if (mCurrentWebViewPackage != null) {
                                oldProviderName = mCurrentWebViewPackage.packageName;
                                if (changedState == WebViewUpdateService.PACKAGE_CHANGED
                                        && newPackage.packageName.equals(oldProviderName)) {
                                    // If we don't change package name we should only rerun the
                                    // preparation phase if the current package has been replaced
                                    // (not if it has been enabled/disabled).
                                    return;
                                }
                            }
                            // Only trigger update actions if the updated package is the one
                            // that will be used, or the one that was in use before the
                            // update, or if we haven't seen a valid WebView package before.
                            updateWebView =
                                provider.packageName.equals(newPackage.packageName)
                                || provider.packageName.equals(oldProviderName)
                                || mCurrentWebViewPackage == null;
                            // We removed the old package if we received an intent to remove
                            // or replace the old package.
                            removedOrChangedOldPackage =
                                provider.packageName.equals(oldProviderName);
                            if (updateWebView) {
                                onWebViewProviderChanged(newPackage);
                            }
                        } catch (WebViewFactory.MissingWebViewPackageException e) {
                            Slog.e(TAG, "Could not find valid WebView package to create " +
                                    "relro with " + e);
                        }
                    }
                    if(updateWebView && !removedOrChangedOldPackage
                            && oldProviderName != null) {
                        // If the provider change is the result of adding or replacing a
                        // package that was not the previous provider then we must kill
                        // packages dependent on the old package ourselves. The framework
                        // only kills dependents of packages that are being removed.
                        mSystemInterface.killPackageDependents(oldProviderName);
                    }
                    return;
                }
            }
        }

        public void prepareWebViewInSystemServer() {
            try {
                synchronized(mLock) {
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
        public String changeProviderAndSetting(String newProviderName) {
            PackageInfo oldPackage = null;
            PackageInfo newPackage = null;
            synchronized(mLock) {
                oldPackage = mCurrentWebViewPackage;
                mSystemInterface.updateUserSetting(mContext, newProviderName);

                try {
                    newPackage = findPreferredWebViewPackage();
                    if (oldPackage != null
                            && newPackage.packageName.equals(oldPackage.packageName)) {
                        // If we don't perform the user change, revert the settings change.
                        mSystemInterface.updateUserSetting(mContext, newPackage.packageName);
                        return newPackage.packageName;
                    }
                } catch (WebViewFactory.MissingWebViewPackageException e) {
                    Slog.e(TAG, "Tried to change WebView provider but failed to fetch WebView " +
                            "package " + e);
                    // If we don't perform the user change but don't have an installed WebView
                    // package, we will have changed the setting and it will be used when a package
                    // is available.
                    return newProviderName;
                }
                onWebViewProviderChanged(newPackage);
            }
            // Kill apps using the old provider
            if (oldPackage != null) {
                mSystemInterface.killPackageDependents(oldPackage.packageName);
            }
            return newPackage.packageName;
        }

        /**
         * This is called when we change WebView provider, either when the current provider is
         * updated or a new provider is chosen / takes precedence.
         */
        private void onWebViewProviderChanged(PackageInfo newPackage) {
            synchronized(mLock) {
                mAnyWebViewInstalled = true;
                if (mNumRelroCreationsStarted == mNumRelroCreationsFinished) {
                    mCurrentWebViewPackage = newPackage;
                    mSystemInterface.updateUserSetting(mContext, newPackage.packageName);

                    // The relro creations might 'finish' (not start at all) before
                    // WebViewFactory.onWebViewProviderChanged which means we might not know the
                    // number of started creations before they finish.
                    mNumRelroCreationsStarted = NUMBER_OF_RELROS_UNKNOWN;
                    mNumRelroCreationsFinished = 0;
                    mNumRelroCreationsStarted =
                        mSystemInterface.onWebViewProviderChanged(newPackage);
                    // If the relro creations finish before we know the number of started creations
                    // we will have to do any cleanup/notifying here.
                    checkIfRelrosDoneLocked();
                } else {
                    mWebViewPackageDirty = true;
                }
            }
        }

        private ProviderAndPackageInfo[] getValidWebViewPackagesAndInfos() {
            WebViewProviderInfo[] allProviders = mSystemInterface.getWebViewPackages();
            List<ProviderAndPackageInfo> providers = new ArrayList<>();
            for(int n = 0; n < allProviders.length; n++) {
                try {
                    PackageInfo packageInfo =
                        mSystemInterface.getPackageInfoForProvider(allProviders[n]);
                    if (isValidProvider(allProviders[n], packageInfo)) {
                        providers.add(new ProviderAndPackageInfo(allProviders[n], packageInfo));
                    }
                } catch (NameNotFoundException e) {
                    // Don't add non-existent packages
                }
            }
            return providers.toArray(new ProviderAndPackageInfo[providers.size()]);
        }

        /**
         * Fetch only the currently valid WebView packages.
         **/
        public WebViewProviderInfo[] getValidWebViewPackages() {
            ProviderAndPackageInfo[] providersAndPackageInfos = getValidWebViewPackagesAndInfos();
            WebViewProviderInfo[] providers =
                new WebViewProviderInfo[providersAndPackageInfos.length];
            for(int n = 0; n < providersAndPackageInfos.length; n++) {
                providers[n] = providersAndPackageInfos[n].provider;
            }
            return providers;
        }


        private class ProviderAndPackageInfo {
            public final WebViewProviderInfo provider;
            public final PackageInfo packageInfo;

            public ProviderAndPackageInfo(WebViewProviderInfo provider, PackageInfo packageInfo) {
                this.provider = provider;
                this.packageInfo = packageInfo;
            }
        }

        /**
         * Returns either the package info of the WebView provider determined in the following way:
         * If the user has chosen a provider then use that if it is valid,
         * otherwise use the first package in the webview priority list that is valid.
         *
         */
        private PackageInfo findPreferredWebViewPackage() {
            ProviderAndPackageInfo[] providers = getValidWebViewPackagesAndInfos();

            String userChosenProvider = mSystemInterface.getUserChosenWebViewProvider(mContext);

            // If the user has chosen provider, use that
            for (ProviderAndPackageInfo providerAndPackage : providers) {
                if (providerAndPackage.provider.packageName.equals(userChosenProvider)
                        && isEnabledPackage(providerAndPackage.packageInfo)) {
                    return providerAndPackage.packageInfo;
                }
            }

            // User did not choose, or the choice failed; use the most stable provider that is
            // enabled and available by default (not through user choice).
            for (ProviderAndPackageInfo providerAndPackage : providers) {
                if (providerAndPackage.provider.availableByDefault
                        && isEnabledPackage(providerAndPackage.packageInfo)) {
                    return providerAndPackage.packageInfo;
                }
            }

            // Could not find any enabled package either, use the most stable provider.
            for (ProviderAndPackageInfo providerAndPackage : providers) {
                return providerAndPackage.packageInfo;
            }

            mAnyWebViewInstalled = false;
            throw new WebViewFactory.MissingWebViewPackageException(
                    "Could not find a loadable WebView package");
        }

        public void notifyRelroCreationCompleted() {
            synchronized (mLock) {
                mNumRelroCreationsFinished++;
                checkIfRelrosDoneLocked();
            }
        }

        public WebViewProviderResponse waitForAndGetProvider() {
            PackageInfo webViewPackage = null;
            final long NS_PER_MS = 1000000;
            final long timeoutTimeMs = System.nanoTime() / NS_PER_MS + WAIT_TIMEOUT_MS;
            boolean webViewReady = false;
            int webViewStatus = WebViewFactory.LIBLOAD_SUCCESS;
            synchronized (mLock) {
                webViewReady = webViewIsReadyLocked();
                while (!webViewReady) {
                    final long timeNowMs = System.nanoTime() / NS_PER_MS;
                    if (timeNowMs >= timeoutTimeMs) break;
                    try {
                        mLock.wait(timeoutTimeMs - timeNowMs);
                    } catch (InterruptedException e) {}
                    webViewReady = webViewIsReadyLocked();
                }
                // Make sure we return the provider that was used to create the relro file
                webViewPackage = mCurrentWebViewPackage;
                if (webViewReady) {
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

        public String getCurrentWebViewPackageName() {
            synchronized(mLock) {
                if (mCurrentWebViewPackage == null)
                    return null;
                return mCurrentWebViewPackage.packageName;
            }
        }

        /**
         * Returns whether WebView is ready and is not going to go through its preparation phase
         * again directly.
         */
        private boolean webViewIsReadyLocked() {
            return !mWebViewPackageDirty
                && (mNumRelroCreationsStarted == mNumRelroCreationsFinished)
                // The current package might be replaced though we haven't received an intent
                // declaring this yet, the following flag makes anyone loading WebView to wait in
                // this case.
                && mAnyWebViewInstalled;
        }

        private void checkIfRelrosDoneLocked() {
            if (mNumRelroCreationsStarted == mNumRelroCreationsFinished) {
                if (mWebViewPackageDirty) {
                    mWebViewPackageDirty = false;
                    // If we have changed provider since we started the relro creation we need to
                    // redo the whole process using the new package instead.
                    try {
                        PackageInfo newPackage = findPreferredWebViewPackage();
                        onWebViewProviderChanged(newPackage);
                    } catch (WebViewFactory.MissingWebViewPackageException e) {
                        // If we can't find any valid WebView package we are now in a state where
                        // mAnyWebViewInstalled is false, so loading WebView will be blocked and we
                        // should simply wait until we receive an intent declaring a new package was
                        // installed.
                    }
                } else {
                    mLock.notifyAll();
                }
            }
        }

        /**
         * Returns whether this provider is valid for use as a WebView provider.
         */
        public boolean isValidProvider(WebViewProviderInfo configInfo,
                PackageInfo packageInfo) {
            if ((packageInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0
                    && packageInfo.versionCode < getMinimumVersionCode()
                    && !mSystemInterface.systemIsDebuggable()) {
                // Non-system package webview providers may be downgraded arbitrarily low, prevent
                // that by enforcing minimum version code. This check is only enforced for user
                // builds.
                return false;
            }
            if (providerHasValidSignature(configInfo, packageInfo, mSystemInterface) &&
                    WebViewFactory.getWebViewLibrary(packageInfo.applicationInfo) != null) {
                return true;
            }
            return false;
        }

        /**
         * Gets the minimum version code allowed for a valid provider. It is the minimum versionCode
         * of all available-by-default and non-fallback WebView provider packages. If there is no
         * such WebView provider package on the system, then return -1, which means all positive
         * versionCode WebView packages are accepted.
         */
        private int getMinimumVersionCode() {
            if (mMinimumVersionCode > 0) {
                return mMinimumVersionCode;
            }

            for (WebViewProviderInfo provider : mSystemInterface.getWebViewPackages()) {
                if (provider.availableByDefault && !provider.isFallback) {
                    try {
                        int versionCode =
                            mSystemInterface.getFactoryPackageVersion(provider.packageName);
                        if (mMinimumVersionCode < 0 || versionCode < mMinimumVersionCode) {
                            mMinimumVersionCode = versionCode;
                        }
                    } catch (NameNotFoundException e) {
                        // Safe to ignore.
                    }
                }
            }

            return mMinimumVersionCode;
        }
    }

    private static boolean providerHasValidSignature(WebViewProviderInfo provider,
            PackageInfo packageInfo, SystemInterface systemInterface) {
        if (systemInterface.systemIsDebuggable()) {
            return true;
        }
        Signature[] packageSignatures;
        // If no signature is declared, instead check whether the package is included in the
        // system.
        if (provider.signatures == null || provider.signatures.length == 0) {
            return packageInfo.applicationInfo.isSystemApp();
        }
        packageSignatures = packageInfo.signatures;
        if (packageSignatures.length != 1)
            return false;

        final byte[] packageSignature = packageSignatures[0].toByteArray();
        // Return whether the package signature matches any of the valid signatures
        for (String signature : provider.signatures) {
            final byte[] validSignature = Base64.decode(signature, Base64.DEFAULT);
            if (Arrays.equals(packageSignature, validSignature))
                return true;
        }
        return false;
    }

    /**
     * Returns whether the given package is enabled.
     * This state can be changed by the user from Settings->Apps
     */
    private static boolean isEnabledPackage(PackageInfo packageInfo) {
        return packageInfo.applicationInfo.enabled;
    }

}
