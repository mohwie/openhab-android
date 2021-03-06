/*
 * Copyright (c) 2010-2016, openHAB.org and others.
 *
 *   All rights reserved. This program and the accompanying materials
 *   are made available under the terms of the Eclipse Public License v1.0
 *   which accompanies this distribution, and is available at
 *   http://www.eclipse.org/legal/epl-v10.html
 */

package org.openhab.habdroid.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.PorterDuff;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.nfc.NfcAdapter;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.RelativeSizeSpan;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;
import android.view.ViewStub;
import android.view.WindowManager;
import android.widget.ProgressBar;
import androidx.annotation.ColorInt;
import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.navigation.NavigationView;
import com.google.android.material.snackbar.Snackbar;
import okhttp3.Headers;
import okhttp3.Request;
import org.openhab.habdroid.R;
import org.openhab.habdroid.background.BackgroundTasksManager;
import org.openhab.habdroid.core.CloudMessagingHelper;
import org.openhab.habdroid.core.OnUpdateBroadcastReceiver;
import org.openhab.habdroid.core.VoiceService;
import org.openhab.habdroid.core.connection.CloudConnection;
import org.openhab.habdroid.core.connection.Connection;
import org.openhab.habdroid.core.connection.ConnectionFactory;
import org.openhab.habdroid.core.connection.DemoConnection;
import org.openhab.habdroid.core.connection.exception.ConnectionException;
import org.openhab.habdroid.core.connection.exception.NetworkNotAvailableException;
import org.openhab.habdroid.core.connection.exception.NetworkNotSupportedException;
import org.openhab.habdroid.core.connection.exception.NoUrlInformationException;
import org.openhab.habdroid.model.LinkedPage;
import org.openhab.habdroid.model.NfcTag;
import org.openhab.habdroid.model.ServerProperties;
import org.openhab.habdroid.model.Sitemap;
import org.openhab.habdroid.ui.activity.ContentController;
import org.openhab.habdroid.ui.homescreenwidget.VoiceWidget;
import org.openhab.habdroid.ui.homescreenwidget.VoiceWidgetWithIcon;
import org.openhab.habdroid.util.AsyncHttpClient;
import org.openhab.habdroid.util.AsyncServiceResolver;
import org.openhab.habdroid.util.Constants;
import org.openhab.habdroid.util.Util;

import java.lang.reflect.Constructor;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import javax.jmdns.ServiceInfo;

import static org.openhab.habdroid.util.Constants.PREV_SERVER_FLAGS;

public class MainActivity extends AbstractBaseActivity implements
        AsyncServiceResolver.Listener, ConnectionFactory.UpdateListener {
    public static final String ACTION_NOTIFICATION_SELECTED =
            "org.openhab.habdroid.action.NOTIFICATION_SELECTED";
    public static final String ACTION_HABPANEL_SELECTED =
            "org.openhab.habdroid.action.HABPANEL_SELECTED";
    public static final String ACTION_VOICE_RECOGNITION_SELECTED =
            "org.openhab.habdroid.action.VOICE_SELECTED";
    public static final String ACTION_SITEMAP_SELECTED =
            "org.openhab.habdroid.action.SITEMAP_SELECTED";
    public static final String EXTRA_SITEMAP_URL = "sitemapUrl";
    public static final String EXTRA_PERSISTED_NOTIFICATION_ID = "persistedNotificationId";

    private static final String TAG = MainActivity.class.getSimpleName();

    // Activities request codes
    private static final int INTRO_REQUEST_CODE = 1001;
    private static final int SETTINGS_REQUEST_CODE = 1002;
    private static final int WRITE_NFC_TAG_REQUEST_CODE = 1003;
    private static final int INFO_REQUEST_CODE = 1004;
    // Drawer item codes
    private static final int GROUP_ID_SITEMAPS = 1;

    private SharedPreferences mPrefs;
    private AsyncServiceResolver mServiceResolver;
    private DrawerLayout mDrawerLayout;
    private ActionBarDrawerToggle mDrawerToggle;
    private Menu mDrawerMenu;
    private ColorStateList mDrawerIconTintList;
    private RecyclerView.RecycledViewPool mViewPool;
    private ProgressBar mProgressBar;
    private Dialog mSitemapSelectionDialog;
    private Snackbar mLastSnackbar;
    private Connection mConnection;

    private String mPendingOpenSitemapUrl;
    private String mPendingOpenedNotificationId;
    private boolean mShouldOpenHabpanel;
    private boolean mShouldLaunchVoiceRecognition;
    private Sitemap mSelectedSitemap;
    private ContentController mController;
    private ServerProperties mServerProperties;
    private ServerProperties.UpdateHandle mPropsUpdateHandle;
    private boolean mStarted;
    private ShortcutManager mShortcutManager;

    /**
     * Daydreaming gets us into a funk when in fullscreen, this allows us to
     * reset ourselves to fullscreen.
     * @author Dan Cunningham
     */
    private BroadcastReceiver mDreamReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i("INTENTFILTER", "Recieved intent: " + intent.toString());
            checkFullscreen();
        }
    };

    /**
     * This method is called when activity receives a new intent while running
     */
    @Override
    protected void onNewIntent(Intent intent) {
        Log.d(TAG, "onNewIntent()");
        processIntent(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "onCreate()");

        // Set default values, false means do it one time during the very first launch
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);

        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);

        // Disable screen timeout if set in preferences
        if (mPrefs.getBoolean(Constants.PREFERENCE_SCREENTIMEROFF, false)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        super.onCreate(savedInstanceState);

        String controllerClassName = getResources().getString(R.string.controller_class);
        try {
            Class<?> controllerClass = Class.forName(controllerClassName);
            Constructor<?> constructor = controllerClass.getConstructor(MainActivity.class);
            mController = (ContentController) constructor.newInstance(this);
        } catch (Exception e) {
            Log.wtf(TAG, "Could not instantiate activity controller class '"
                    + controllerClassName + "'");
            throw new RuntimeException(e);
        }

        setContentView(R.layout.activity_main);
        // inflate the controller dependent content view
        ViewStub contentStub = findViewById(R.id.content_stub);
        mController.inflateViews(contentStub);

        setupToolbar();
        setupDrawer();

        mViewPool = new RecyclerView.RecycledViewPool();

        // Check if we have openHAB page url in saved instance state?
        if (savedInstanceState != null) {
            mServerProperties = savedInstanceState.getParcelable("serverProperties");
            mSelectedSitemap = savedInstanceState.getParcelable("sitemap");
            int lastConnectionHash = savedInstanceState.getInt("connectionHash");
            if (lastConnectionHash != -1) {
                try {
                    Connection c = ConnectionFactory.getUsableConnection();
                    if (c != null && c.hashCode() == lastConnectionHash) {
                        mConnection = c;
                    }
                } catch (ConnectionException e) {
                    // ignored
                }
            }

            mController.onRestoreInstanceState(savedInstanceState);
            String lastControllerClass = savedInstanceState.getString("controller");
            if (!mController.getClass().getCanonicalName().equals(lastControllerClass)) {
                // Our controller type changed, so we need to make the new controller aware of the
                // page hierarchy. If the controller didn't change, the hierarchy will be restored
                // via the fragment state restoration.
                mController.recreateFragmentState();
            }
            if (savedInstanceState.getBoolean("isSitemapSelectionDialogShown")) {
                showSitemapSelectionDialog();
            }
        }

        processIntent(getIntent());

        if (isFullscreenEnabled()) {
            IntentFilter filter = new IntentFilter(Intent.ACTION_DREAMING_STARTED);
            filter.addAction(Intent.ACTION_DREAMING_STOPPED);
            registerReceiver(mDreamReceiver, filter);
        }

        //  Create a new boolean and preference and set it to true
        boolean isFirstStart = mPrefs.getBoolean(Constants.PREFERENCE_FIRST_START, true);

        SharedPreferences.Editor prefsEditor = mPrefs.edit();
        //  If the activity has never started before...
        if (isFirstStart) {
            //  Launch app intro
            final Intent i = new Intent(MainActivity.this, IntroActivity.class);
            startActivityForResult(i, INTRO_REQUEST_CODE);

            prefsEditor.putBoolean(Constants.PREFERENCE_FIRST_START, false);
        }
        OnUpdateBroadcastReceiver.updateComparableVersion(prefsEditor);
        prefsEditor.apply();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            mShortcutManager = getSystemService(ShortcutManager.class);
        }

        final boolean isSpeechRecognizerAvailable = SpeechRecognizer.isRecognitionAvailable(this);
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... voids) {
                manageVoiceRecognitionShortcut(isSpeechRecognizerAvailable);
                setVoiceWidgetComponentEnabledSetting(VoiceWidget.class,
                        isSpeechRecognizerAvailable);
                setVoiceWidgetComponentEnabledSetting(VoiceWidgetWithIcon.class,
                        isSpeechRecognizerAvailable);
                return null;
            }
        }.execute();
    }

    private void handleConnectionChange() {
        if (mConnection instanceof DemoConnection) {
            showDemoModeHintSnackbar();
        } else {
            boolean hasLocalAndRemote =
                    ConnectionFactory.getConnection(Connection.TYPE_LOCAL) != null
                    && ConnectionFactory.getConnection(Connection.TYPE_REMOTE) != null;
            int type = mConnection.getConnectionType();
            if (hasLocalAndRemote && type == Connection.TYPE_LOCAL) {
                showSnackbar(R.string.info_conn_url);
            } else if (hasLocalAndRemote && type == Connection.TYPE_REMOTE) {
                showSnackbar(R.string.info_conn_rem_url);
            }
        }
        queryServerProperties();
    }

    public void enableWifiAndIndicateStartup() {
        WifiManager wifiManager =
                (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        wifiManager.setWifiEnabled(true);
        mController.updateConnection(null, getString(R.string.waiting_for_wifi),
                R.drawable.ic_wifi_strength_outline_black_24dp);
    }

    public void retryServerPropertyQuery() {
        mController.clearServerCommunicationFailure();
        queryServerProperties();
    }

    private void queryServerProperties() {
        if (mPropsUpdateHandle != null) {
            mPropsUpdateHandle.cancel();
        }
        ServerProperties.UpdateSuccessCallback successCb = props -> {
            mServerProperties = props;
            updateSitemapAndHabpanelDrawerItems();
            if (props.sitemaps().isEmpty()) {
                Log.e(TAG, "openHAB returned empty sitemap list");
                mController.indicateServerCommunicationFailure(
                        getString(R.string.error_empty_sitemap_list));
            } else {
                Sitemap sitemap = selectConfiguredSitemapFromList();
                if (sitemap != null) {
                    openSitemap(sitemap);
                } else {
                    showSitemapSelectionDialog();
                }
            }
            if (!(getConnection() instanceof DemoConnection)) {
                PreferenceManager.getDefaultSharedPreferences(this)
                        .edit()
                        .putInt(PREV_SERVER_FLAGS, props.flags())
                        .apply();
            }
            openHabpanelIfNeeded();
            launchVoiceRecognitionIfNeeded();
            openPendingSitemapIfNeeded();
        };
        mPropsUpdateHandle = ServerProperties.fetch(mConnection,
                successCb, this::handlePropertyFetchFailure);
    }

    @Override
    public void onServiceResolved(ServiceInfo serviceInfo) {
        Log.d(TAG, "Service resolved: "
                + serviceInfo.getHostAddresses()[0]
                + " port:" + serviceInfo.getPort());
        String serverUrl = "https://" + serviceInfo.getHostAddresses()[0] + ":"
                + String.valueOf(serviceInfo.getPort()) + "/";

        PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .putString(Constants.PREFERENCE_LOCAL_URL, serverUrl)
                .apply();
        // We'll get a connection update later
        mServiceResolver = null;
    }

    @Override
    public void onServiceResolveFailed() {
        Log.d(TAG, "onServiceResolveFailed()");
        mController.indicateMissingConfiguration(true);
        mServiceResolver = null;
    }

    private void processIntent(Intent intent) {
        Log.d(TAG, "Got intent: " + intent);
        String action = intent.getAction() != null ? intent.getAction() : "";
        switch (action) {
            case NfcAdapter.ACTION_NDEF_DISCOVERED:
            case Intent.ACTION_VIEW:
                NfcTag tag = NfcTag.fromTagData(intent.getData());
                BackgroundTasksManager.enqueueNfcUpdateIfNeeded(this, tag);

                if (tag != null && !TextUtils.isEmpty(tag.sitemap())) {
                    mPendingOpenSitemapUrl = tag.sitemap();
                    openPendingSitemapIfNeeded();
                }

                break;
            case ACTION_NOTIFICATION_SELECTED:
                CloudMessagingHelper.onNotificationSelected(this, intent);
                onNotificationSelected(intent);
                break;
            case ACTION_HABPANEL_SELECTED:
                mShouldOpenHabpanel = true;
                openHabpanelIfNeeded();
                break;
            case ACTION_VOICE_RECOGNITION_SELECTED:
                mShouldLaunchVoiceRecognition = true;
                launchVoiceRecognitionIfNeeded();
                break;
            case ACTION_SITEMAP_SELECTED:
                mPendingOpenSitemapUrl = intent.getStringExtra(EXTRA_SITEMAP_URL);
                openPendingSitemapIfNeeded();
            default:
                break;
        }
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        Log.d(TAG, "onPostCreate()");
        super.onPostCreate(savedInstanceState);
        if (mDrawerToggle != null) {
            // Sync the toggle state after onRestoreInstanceState has occurred.
            mDrawerToggle.syncState();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        Log.d(TAG, "onConfigurationChanged()");
        super.onConfigurationChanged(newConfig);
        if (mDrawerToggle != null) {
            mDrawerToggle.onConfigurationChanged(newConfig);
        }
    }

    @Override
    protected void onResume() {
        Log.d(TAG, "onResume()");
        super.onResume();

        NfcAdapter nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (nfcAdapter != null) {
            Intent intent = new Intent(this, getClass())
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, 0, intent, 0);
            nfcAdapter.enableForegroundDispatch(this, pi, null, null);
        }

        updateTitle();
    }

    @Override
    protected void onPause() {
        Log.d(TAG, "onPause()");
        super.onPause();
        NfcAdapter nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (nfcAdapter != null) {
            nfcAdapter.disableForegroundDispatch(this);
        }
    }

    @Override
    public void onAvailableConnectionChanged() {
        Log.d(TAG, "onAvailableConnectionChanged()");
        Connection newConnection;
        ConnectionException failureReason;

        try {
            newConnection = ConnectionFactory.getUsableConnection();
            failureReason = null;
        } catch (ConnectionException e) {
            newConnection = null;
            failureReason = e;
        }

        updateNotificationDrawerItem();

        if (newConnection != null && newConnection == mConnection) {
            return;
        }

        mConnection = newConnection;
        hideSnackbar();
        mServerProperties = null;
        mSelectedSitemap = null;

        // Handle pending NFC tag if initial connection determination finished
        openPendingSitemapIfNeeded();
        openHabpanelIfNeeded();
        launchVoiceRecognitionIfNeeded();

        if (newConnection != null) {
            handleConnectionChange();
            mController.updateConnection(newConnection, null, 0);
        } else {
            if (failureReason instanceof NoUrlInformationException) {
                NoUrlInformationException nuie = (NoUrlInformationException) failureReason;
                // Attempt resolving only if we're connected locally and
                // no local connection is configured yet
                if (nuie.wouldHaveUsedLocalConnection()
                        && ConnectionFactory.getConnection(Connection.TYPE_LOCAL) == null) {
                    if (mServiceResolver == null) {
                        mServiceResolver = new AsyncServiceResolver(this, this,
                                getString(R.string.openhab_service_type));
                        mServiceResolver.start();
                        mController.updateConnection(null,
                                getString(R.string.resolving_openhab),
                                R.drawable.ic_openhab_appicon_340dp /*FIXME?*/);
                    }
                } else {
                    mController.indicateMissingConfiguration(false);
                }
            } else if (failureReason != null) {
                WifiManager wifiManager = (WifiManager)
                        getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (failureReason instanceof NetworkNotSupportedException) {
                    NetworkInfo info =
                            ((NetworkNotSupportedException) failureReason).getNetworkInfo();
                    mController.indicateNoNetwork(
                            getString(R.string.error_network_type_unsupported, info.getTypeName()),
                            false);
                } else if (failureReason instanceof NetworkNotAvailableException
                        && !wifiManager.isWifiEnabled()) {
                    mController.indicateNoNetwork(
                            getString(R.string.error_wifi_not_available), true);
                } else {
                    mController.indicateNoNetwork(getString(R.string.error_network_not_available),
                            false);
                }
            } else {
                mController.updateConnection(null, null, 0);
            }
        }
        mViewPool.clear();
        updateSitemapAndHabpanelDrawerItems();
        invalidateOptionsMenu();
        updateTitle();
    }

    @Override
    public void onCloudConnectionChanged(CloudConnection connection) {
        Log.d(TAG, "onCloudConnectionChanged()");
        updateNotificationDrawerItem();
        openNotificationsPageIfNeeded();
    }

    @Override
    protected void onStart() {
        Log.d(TAG, "onStart()");
        super.onStart();
        mStarted = true;

        ConnectionFactory.addListener(this);

        onAvailableConnectionChanged();
        updateNotificationDrawerItem();

        if (mConnection != null && mServerProperties == null) {
            mController.clearServerCommunicationFailure();
            queryServerProperties();
        }
        openPendingSitemapIfNeeded();
        openNotificationsPageIfNeeded();
        openHabpanelIfNeeded();
        launchVoiceRecognitionIfNeeded();
    }

    @Override
    public void onStop() {
        Log.d(TAG, "onStop()");
        mStarted = false;
        super.onStop();
        ConnectionFactory.removeListener(this);
        if (mServiceResolver != null && mServiceResolver.isAlive()) {
            mServiceResolver.interrupt();
            mServiceResolver = null;
        }
        if (mSitemapSelectionDialog != null && mSitemapSelectionDialog.isShowing()) {
            mSitemapSelectionDialog.dismiss();
        }
        if (mPropsUpdateHandle != null) {
            mPropsUpdateHandle.cancel();
        }
    }

    public void triggerPageUpdate(String pageUrl, boolean forceReload) {
        mController.triggerPageUpdate(pageUrl, forceReload);
    }

    private void setupToolbar() {
        Toolbar toolbar = findViewById(R.id.openhab_toolbar);
        setSupportActionBar(toolbar);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setHomeButtonEnabled(true);
        }

        // ProgressBar layout params inside the toolbar have to be done programmatically
        // because it doesn't work through layout file :-(
        mProgressBar = toolbar.findViewById(R.id.toolbar_progress_bar);
        mProgressBar.setLayoutParams(
                new Toolbar.LayoutParams(Gravity.END | Gravity.CENTER_VERTICAL));
        setProgressIndicatorVisible(false);
    }

    private void setupDrawer() {
        mDrawerLayout = findViewById(R.id.drawer_layout);
        mDrawerToggle = new ActionBarDrawerToggle(this, mDrawerLayout,
                R.string.drawer_open, R.string.drawer_close);
        mDrawerLayout.addDrawerListener(mDrawerToggle);
        mDrawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerOpened(View drawerView) {
                if (mServerProperties != null && mPropsUpdateHandle == null) {
                    mPropsUpdateHandle = ServerProperties.updateSitemaps(mServerProperties,
                            mConnection,
                            props -> {
                                mServerProperties = props;
                                openPendingSitemapIfNeeded();
                                updateSitemapAndHabpanelDrawerItems();
                            },
                            MainActivity.this::handlePropertyFetchFailure);
                }
            }
        });
        mDrawerLayout.setDrawerShadow(R.drawable.drawer_shadow, GravityCompat.START);

        NavigationView drawerMenu = findViewById(R.id.left_drawer);
        drawerMenu.inflateMenu(R.menu.left_drawer);
        mDrawerMenu = drawerMenu.getMenu();

        // We only want to tint the menu icons, but not our loaded sitemap icons. NavigationView
        // unfortunately doesn't support this directly, so we tint the icon drawables manually
        // instead of letting NavigationView do it.
        mDrawerIconTintList = drawerMenu.getItemIconTintList();
        drawerMenu.setItemIconTintList(null);
        for (int i = 0; i < mDrawerMenu.size(); i++) {
            MenuItem item = mDrawerMenu.getItem(i);
            item.setIcon(applyDrawerIconTint(item.getIcon()));
        }

        drawerMenu.setNavigationItemSelectedListener(item -> {
            mDrawerLayout.closeDrawers();
            switch (item.getItemId()) {
                case R.id.notifications:
                    openNotifications(null);
                    return true;
                case R.id.habpanel:
                    openHabpanel();
                    return true;
                case R.id.settings:
                    Intent settingsIntent = new Intent(MainActivity.this,
                            PreferencesActivity.class);
                    settingsIntent.putExtra(PreferencesActivity.START_EXTRA_SERVER_PROPERTIES,
                            mServerProperties);
                    startActivityForResult(settingsIntent, SETTINGS_REQUEST_CODE);
                    return true;
                case R.id.about:
                    openAbout();
                    return true;
                default:
                    break;
            }
            if (item.getGroupId() == GROUP_ID_SITEMAPS) {
                Sitemap sitemap = mServerProperties.sitemaps().get(item.getItemId());
                openSitemap(sitemap);
                return true;
            }
            return false;
        });
    }

    private void updateNotificationDrawerItem() {
        MenuItem notificationsItem = mDrawerMenu.findItem(R.id.notifications);
        boolean hasCloudConnection = ConnectionFactory.getConnection(Connection.TYPE_CLOUD) != null;
        notificationsItem.setVisible(hasCloudConnection);
        if (hasCloudConnection) {
            manageNotificationShortcut(true);
        }
    }

    private void updateSitemapAndHabpanelDrawerItems() {
        MenuItem sitemapItem = mDrawerMenu.findItem(R.id.sitemaps);
        MenuItem habpanelItem = mDrawerMenu.findItem(R.id.habpanel);
        if (mServerProperties == null) {
            sitemapItem.setVisible(false);
            habpanelItem.setVisible(false);
        } else {
            habpanelItem.setVisible(mServerProperties.hasHabpanelInstalled());
            manageHabpanelShortcut(mServerProperties.hasHabpanelInstalled());
            final String defaultSitemapName =
                    mPrefs.getString(Constants.PREFERENCE_SITEMAP_NAME, "");
            final List<Sitemap> sitemaps = mServerProperties.sitemaps();
            Util.sortSitemapList(sitemaps, defaultSitemapName);

            if (sitemaps.isEmpty()) {
                sitemapItem.setVisible(false);
            } else {
                sitemapItem.setVisible(true);
                SubMenu menu = sitemapItem.getSubMenu();
                menu.clear();

                for (int i = 0; i < sitemaps.size(); i++) {
                    Sitemap sitemap = sitemaps.get(i);
                    MenuItem item = menu.add(GROUP_ID_SITEMAPS, i, i, sitemap.label());
                    loadSitemapIcon(sitemap, item);
                }
            }
        }
    }

    private void loadSitemapIcon(final Sitemap sitemap, final MenuItem item) {
        final String url = sitemap.icon() != null ? Uri.encode(sitemap.iconPath(), "/?=") : null;
        Drawable defaultIcon = ContextCompat.getDrawable(this, R.drawable.ic_openhab_appicon_24dp);
        item.setIcon(applyDrawerIconTint(defaultIcon));

        if (url == null || mConnection == null) {
            return;
        }
        mConnection.getAsyncHttpClient().get(url,
                new AsyncHttpClient.BitmapResponseHandler(defaultIcon.getIntrinsicWidth()) {
            @Override
            public void onFailure(Request request, int statusCode, Throwable error) {
                Log.w(TAG, "Could not fetch icon for sitemap " + sitemap.name());
            }
            @Override
            public void onSuccess(Bitmap bitmap, Headers headers) {
                if (bitmap != null) {
                    item.setIcon(new BitmapDrawable(bitmap));
                }
            }
        });
    }

    private Drawable applyDrawerIconTint(Drawable icon) {
        if (icon == null) {
            return null;
        }
        Drawable wrapped = DrawableCompat.wrap(icon.mutate());
        DrawableCompat.setTintList(wrapped, mDrawerIconTintList);
        return wrapped;
    }

    private void openNotificationsPageIfNeeded() {
        if (mPendingOpenedNotificationId != null && mStarted
                && ConnectionFactory.getConnection(Connection.TYPE_CLOUD) != null) {
            openNotifications(mPendingOpenedNotificationId);
            mPendingOpenedNotificationId = null;
        }
    }

    private void openHabpanelIfNeeded() {
        if (mStarted && mShouldOpenHabpanel && mServerProperties != null
                && mServerProperties.hasHabpanelInstalled()) {
            openHabpanel();
            mShouldOpenHabpanel = false;
        }
    }

    private void launchVoiceRecognitionIfNeeded() {
        if (mStarted && mShouldLaunchVoiceRecognition && mServerProperties != null) {
            launchVoiceRecognition();
            mShouldLaunchVoiceRecognition = false;
        }
    }

    private void openPendingSitemapIfNeeded() {
        if (mStarted && mPendingOpenSitemapUrl != null && mServerProperties != null) {
            buildUrlAndOpenSitemap(mPendingOpenSitemapUrl);
            mPendingOpenSitemapUrl = null;
        }
    }

    private void openAbout() {
        Intent aboutIntent = new Intent(this, AboutActivity.class);
        aboutIntent.putExtra("serverProperties", mServerProperties);

        startActivityForResult(aboutIntent, INFO_REQUEST_CODE);
    }

    private Sitemap selectConfiguredSitemapFromList() {
        SharedPreferences settings =
                PreferenceManager.getDefaultSharedPreferences(this);
        String configuredSitemap = settings.getString(Constants.PREFERENCE_SITEMAP_NAME, "");
        List<Sitemap> sitemaps = mServerProperties.sitemaps();
        final Sitemap result;

        if (sitemaps.size() == 1) {
            // We only have one sitemap, use it
            result = sitemaps.get(0);
        } else if (!configuredSitemap.isEmpty()) {
            // Select configured sitemap if still present, nothing otherwise
            result = Util.getSitemapByName(sitemaps, configuredSitemap);
        } else {
            // Nothing configured -> can't auto-select anything
            result = null;
        }

        Log.d(TAG, "Configured sitemap is '" + configuredSitemap + "', selected " + result);
        boolean hasResult = result != null;
        boolean hasConfigured = !configuredSitemap.isEmpty();
        if (!hasResult && hasConfigured) {
            // clear old configuration
            settings.edit()
                    .remove(Constants.PREFERENCE_SITEMAP_LABEL)
                    .remove(Constants.PREFERENCE_SITEMAP_NAME)
                    .apply();
        } else if (hasResult && (!hasConfigured || !configuredSitemap.equals(result.name()))) {
            // update result
            settings.edit()
                    .putString(Constants.PREFERENCE_SITEMAP_NAME, result.name())
                    .putString(Constants.PREFERENCE_SITEMAP_LABEL, result.label())
                    .apply();
        }

        return result;
    }

    private void showSitemapSelectionDialog() {
        Log.d(TAG, "Opening sitemap selection dialog");
        if (mSitemapSelectionDialog != null && mSitemapSelectionDialog.isShowing()) {
            mSitemapSelectionDialog.dismiss();
        }
        if (isFinishing()) {
            return;
        }

        List<Sitemap> sitemaps = mServerProperties.sitemaps();
        final String[] sitemapLabels = new String[sitemaps.size()];
        for (int i = 0; i < sitemaps.size(); i++) {
            sitemapLabels[i] = sitemaps.get(i).label();
        }
        mSitemapSelectionDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.mainmenu_openhab_selectsitemap)
                .setItems(sitemapLabels, (dialog, which) -> {
                    Sitemap sitemap = sitemaps.get(which);
                    Log.d(TAG, "Selected sitemap " + sitemap);
                    PreferenceManager.getDefaultSharedPreferences(MainActivity.this)
                            .edit()
                            .putString(Constants.PREFERENCE_SITEMAP_NAME, sitemap.name())
                            .putString(Constants.PREFERENCE_SITEMAP_LABEL, sitemap.label())
                            .apply();
                    openSitemap(sitemap);
                })
                .show();
    }

    private void openNotifications(@Nullable String highlightedId) {
        mController.openNotifications(highlightedId);
        mDrawerToggle.setDrawerIndicatorEnabled(false);
    }

    private void openHabpanel() {
        mController.showHabpanel();
        mDrawerToggle.setDrawerIndicatorEnabled(false);
    }

    private void openSitemap(Sitemap sitemap) {
        Log.i(TAG, "Opening sitemap " + sitemap + ", currently selected " + mSelectedSitemap);
        if (mSelectedSitemap != null && mSelectedSitemap.equals(sitemap)) {
            return;
        }
        mSelectedSitemap = sitemap;
        mController.openSitemap(sitemap);
    }

    private void buildUrlAndOpenSitemap(String partUrl) {
        String newPageUrl = String.format(Locale.US, "rest/sitemaps%s", partUrl);
        mController.openPage(newPageUrl);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        Log.d(TAG, "onCreateOptionsMenu()");
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        Log.d(TAG, "onPrepareOptionsMenu()");
        MenuItem voiceRecognitionItem = menu.findItem(R.id.mainmenu_voice_recognition);
        @ColorInt int iconColor = ContextCompat.getColor(this, R.color.light);
        voiceRecognitionItem.setVisible(mConnection != null);
        voiceRecognitionItem.getIcon().setColorFilter(iconColor, PorterDuff.Mode.SRC_IN);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Log.d(TAG, "onOptionsItemSelected()");
        // Handle back navigation arrow
        if (item.getItemId() == android.R.id.home && mController.canGoBack()) {
            mController.goBack();
            return true;
        }

        // Handle hamburger menu
        if (mDrawerToggle.onOptionsItemSelected(item)) {
            return true;
        }

        // Handle menu items
        switch (item.getItemId()) {
            case R.id.mainmenu_voice_recognition:
                launchVoiceRecognition();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        Log.d(TAG, String.format("onActivityResult() requestCode = %d, resultCode = %d",
                requestCode, resultCode));
        switch (requestCode) {
            case SETTINGS_REQUEST_CODE:
                if (data == null) {
                    break;
                }
                if (data.getBooleanExtra(PreferencesActivity.RESULT_EXTRA_SITEMAP_CLEARED, false)
                        && getConnection() != null && mServerProperties != null) {
                    Sitemap sitemap = selectConfiguredSitemapFromList();
                    if (sitemap != null) {
                        openSitemap(sitemap);
                    } else {
                        showSitemapSelectionDialog();
                    }
                }
                if (data.getBooleanExtra(PreferencesActivity.RESULT_EXTRA_THEME_CHANGED, false)) {
                    recreate();
                }
                break;
            case INTRO_REQUEST_CODE:
                break;
            case WRITE_NFC_TAG_REQUEST_CODE:
                Log.d(TAG, "Got back from Write NFC tag");
                break;
            default:
        }
    }

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        Log.d(TAG, "onSaveInstanceState()");
        mStarted = false;
        savedInstanceState.putParcelable("serverProperties", mServerProperties);
        savedInstanceState.putParcelable("sitemap", mSelectedSitemap);
        savedInstanceState.putBoolean("isSitemapSelectionDialogShown",
                mSitemapSelectionDialog != null && mSitemapSelectionDialog.isShowing());
        savedInstanceState.putString("controller", mController.getClass().getCanonicalName());
        savedInstanceState.putInt("connectionHash",
                mConnection != null ? mConnection.hashCode() : -1);
        mController.onSaveInstanceState(savedInstanceState);
        super.onSaveInstanceState(savedInstanceState);
    }

    private void onNotificationSelected(Intent intent) {
        Log.d(TAG, "onNotificationSelected()");
        mPendingOpenedNotificationId = intent.getStringExtra(EXTRA_PERSISTED_NOTIFICATION_ID);
        if (mPendingOpenedNotificationId == null) {
            // mPendingOpenedNotificationId being non-null is used as trigger for
            // opening the notifications page, so use a dummy if it's null
            mPendingOpenedNotificationId = "";
        }
        openNotificationsPageIfNeeded();
    }

    public void onWidgetSelected(LinkedPage linkedPage, WidgetListFragment source) {
        Log.d(TAG, "Got widget link = " + linkedPage.link());
        mController.openPage(linkedPage, source);
    }

    public void updateTitle() {
        CharSequence title = mController.getCurrentTitle();
        setTitle(title != null ? title : getString(R.string.app_name));
        mDrawerToggle.setDrawerIndicatorEnabled(!mController.canGoBack());
    }

    @Override
    public void onBackPressed() {
        Log.d(TAG, "onBackPressed()");
        if (mController.canGoBack()) {
            mController.goBack();
        } else if (!isFullscreenEnabled()) {
            // Only handle back action in non-fullscreen mode, as we don't want to exit
            // the app via back button in fullscreen mode
            super.onBackPressed();
        }
    }

    public RecyclerView.RecycledViewPool getViewPool() {
        return mViewPool;
    }

    public void setProgressIndicatorVisible(boolean visible) {
        if (mProgressBar != null) {
            mProgressBar.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        }
    }

    private void launchVoiceRecognition() {
        Intent callbackIntent = new Intent(this, VoiceService.class);
        PendingIntent openhabPendingIntent = PendingIntent.getService(this, 0, callbackIntent, 0);

        Intent speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        // Display an hint to the user about what he should say.
        speechIntent.putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.info_voice_input));
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        speechIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        speechIntent.putExtra(RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT, openhabPendingIntent);

        try {
            startActivity(speechIntent);
        } catch (ActivityNotFoundException speechRecognizerNotFoundException) {
            showSnackbar(R.string.error_no_speech_to_text_app_found, R.string.install, v -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(
                            "market://details?id=com.google.android.googlequicksearchbox")));
                } catch (ActivityNotFoundException appStoreNotFoundException) {
                    Util.openInBrowser(this, "http://play.google.com/store/apps/details?id=com.google.android.googlequicksearchbox");
                }
            });
        }
    }

    public void showRefreshHintSnackbarIfNeeded() {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (prefs.getBoolean(Constants.PREFERENCE_SWIPE_REFRESH_EXPLAINED, false)) {
            return;
        }

        showSnackbar(R.string.swipe_to_refresh_description, R.string.swipe_to_refresh_dismiss,
                v -> {
            prefs.edit()
                    .putBoolean(Constants.PREFERENCE_SWIPE_REFRESH_EXPLAINED, true)
                    .apply();
        });
    }

    public void showDemoModeHintSnackbar() {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        showSnackbar(R.string.info_demo_mode_short, R.string.turn_off, v -> {
            prefs.edit()
                    .putBoolean(Constants.PREFERENCE_DEMOMODE, false)
                    .apply();
        });
    }

    private void showSnackbar(@StringRes int messageResId) {
        showSnackbar(messageResId, 0, null);
    }

    private void showSnackbar(@StringRes int messageResId, @StringRes int actionResId,
            View.OnClickListener onClickListener) {
        hideSnackbar();
        mLastSnackbar = Snackbar.make(findViewById(android.R.id.content), messageResId,
                Snackbar.LENGTH_LONG);
        if (actionResId != 0 && onClickListener != null) {
            mLastSnackbar.setAction(actionResId, onClickListener);
        }
        mLastSnackbar.show();
    }

    private void hideSnackbar() {
        if (mLastSnackbar != null) {
            mLastSnackbar.dismiss();
            mLastSnackbar = null;
        }
    }

    private void handlePropertyFetchFailure(Request request, int statusCode, Throwable error) {
        Log.e(TAG, "Error: " + error.toString(), error);
        Log.e(TAG, "HTTP status code: " + statusCode);
        CharSequence message = Util.getHumanReadableErrorMessage(this,
                request.url().toString(), statusCode, error);
        SharedPreferences settings =
                PreferenceManager.getDefaultSharedPreferences(MainActivity.this);
        if (settings.getBoolean(Constants.PREFERENCE_DEBUG_MESSAGES, false)) {
            SpannableStringBuilder builder = new SpannableStringBuilder(message);
            int detailsStart = builder.length();

            builder.append("\n\nURL: ").append(request.url().toString());

            String authHeader = request.header("Authorization");
            if (authHeader != null && authHeader.startsWith("Basic")) {
                String base64Credentials = authHeader.substring("Basic".length()).trim();
                String credentials = new String(Base64.decode(base64Credentials, Base64.DEFAULT),
                        Charset.forName("UTF-8"));
                builder.append("\nUsername: ")
                        .append(credentials.substring(0, credentials.indexOf(":")));
            }

            builder.append("\nException stack:\n");

            int exceptionStart = builder.length();
            Throwable cause = error;
            do {
                builder.append(cause.toString()).append('\n');
                error = cause;
                cause = error.getCause();
            } while (cause != null && error != cause);

            builder.setSpan(new RelativeSizeSpan(0.8f), detailsStart, exceptionStart,
                    SpannableStringBuilder.SPAN_INCLUSIVE_EXCLUSIVE);
            builder.setSpan(new RelativeSizeSpan(0.6f), exceptionStart, builder.length(),
                    SpannableStringBuilder.SPAN_INCLUSIVE_EXCLUSIVE);
            message = builder;
        }

        mController.indicateServerCommunicationFailure(message);
        mPropsUpdateHandle = null;
    }

    public boolean isStarted() {
        return mStarted;
    }

    public ServerProperties getServerProperties() {
        return mServerProperties;
    }

    public Connection getConnection() {
        return mConnection;
    }

    private void manageHabpanelShortcut(boolean visible) {
        manageShortcut(visible, "habpanel", ACTION_HABPANEL_SELECTED,
                R.string.mainmenu_openhab_habpanel, R.mipmap.ic_shortcut_habpanel,
                R.string.app_shortcut_diabled_habpanel);
    }

    private void manageNotificationShortcut(boolean visible) {
        manageShortcut(visible, "notification", ACTION_NOTIFICATION_SELECTED,
                R.string.app_notifications, R.mipmap.ic_shortcut_notifications,
                R.string.app_shortcut_diabled_notifications);;
    }

    private void manageVoiceRecognitionShortcut(boolean visible) {
        manageShortcut(visible, "voice_recognition", ACTION_VOICE_RECOGNITION_SELECTED,
                R.string.mainmenu_openhab_voice_recognition,
                R.mipmap.ic_shortcut_voice_recognition,
                R.string.app_shortcut_diabled_voice_recognition);
    }

    private void manageShortcut(boolean visible, String id, String action,
            @StringRes int shortLabel, @DrawableRes int icon, @StringRes int disableMessage) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
            return;
        }
        if (visible) {
            Intent intent = new Intent(this, MainActivity.class);
            intent.setAction(action);
            ShortcutInfo shortcut = new ShortcutInfo.Builder(this, id)
                    .setShortLabel(getString(shortLabel))
                    .setIcon(Icon.createWithResource(this, icon))
                    .setIntent(intent)
                    .build();
            mShortcutManager.addDynamicShortcuts(Collections.singletonList(shortcut));
        } else {
            mShortcutManager.disableShortcuts(Collections.singletonList(id),
                    getString(disableMessage));
        }
    }

    private void setVoiceWidgetComponentEnabledSetting(Class<?> component,
            boolean isSpeechRecognizerAvailable) {
        ComponentName voiceWidget = new ComponentName(this, component);
        PackageManager pm = getPackageManager();
        int newState = isSpeechRecognizerAvailable ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        pm.setComponentEnabledSetting(voiceWidget, newState, PackageManager.DONT_KILL_APP);
    }
}