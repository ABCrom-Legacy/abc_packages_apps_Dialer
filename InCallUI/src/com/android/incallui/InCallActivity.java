/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.incallui;

import android.app.ActionBar;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.DialogFragment;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.DialogInterface.OnCancelListener;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Point;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Trace;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccountHandle;
import android.text.TextUtils;
import android.view.Display;
import android.view.MenuItem;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import com.android.phone.common.animation.AnimUtils;
import com.android.phone.common.animation.AnimationListenerAdapter;
import com.android.contacts.common.interactions.TouchPointManager;
import com.android.contacts.common.widget.SelectPhoneAccountDialogFragment;
import com.android.contacts.common.widget.SelectPhoneAccountDialogFragment.SelectPhoneAccountListener;
import com.android.incallui.Call.State;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Main activity that the user interacts with while in a live call.
 */
public class InCallActivity extends Activity implements FragmentDisplayManager {

    public static final String TAG = InCallActivity.class.getSimpleName();

    public static final String SHOW_DIALPAD_EXTRA = "InCallActivity.show_dialpad";
    public static final String DIALPAD_TEXT_EXTRA = "InCallActivity.dialpad_text";
    public static final String NEW_OUTGOING_CALL_EXTRA = "InCallActivity.new_outgoing_call";

    private static final String TAG_DIALPAD_FRAGMENT = "tag_dialpad_fragment";
    private static final String TAG_CONFERENCE_FRAGMENT = "tag_conference_manager_fragment";
    private static final String TAG_CALLCARD_FRAGMENT = "tag_callcard_fragment";
    private static final String TAG_ANSWER_FRAGMENT = "tag_answer_fragment";
    private static final String TAG_SELECT_ACCT_FRAGMENT = "tag_select_acct_fragment";

    private CallButtonFragment mCallButtonFragment;
    private CallCardFragment mCallCardFragment;
    private AnswerFragment mAnswerFragment;
    private DialpadFragment mDialpadFragment;
    private ConferenceManagerFragment mConferenceManagerFragment;
    private FragmentManager mChildFragmentManager;

    private boolean mIsForegroundActivity;
    private AlertDialog mDialog;

    /** Use to pass 'showDialpad' from {@link #onNewIntent} to {@link #onResume} */
    private boolean mShowDialpadRequested;

    /** Use to determine if the dialpad should be animated on show. */
    private boolean mAnimateDialpadOnShow;

    /** Use to determine the DTMF Text which should be pre-populated in the dialpad. */
    private String mDtmfText;

    /** Use to pass parameters for showing the PostCharDialog to {@link #onResume} */
    private boolean mShowPostCharWaitDialogOnResume;
    private String mShowPostCharWaitDialogCallId;
    private String mShowPostCharWaitDialogChars;

    private boolean mIsLandscape;
    private Animation mSlideIn;
    private Animation mSlideOut;
    private boolean mDismissKeyguard = false;

    AnimationListenerAdapter mSlideOutListener = new AnimationListenerAdapter() {
        @Override
        public void onAnimationEnd(Animation animation) {
            showFragment(TAG_DIALPAD_FRAGMENT, false, true);
        }
    };

    private SelectPhoneAccountListener mSelectAcctListener = new SelectPhoneAccountListener() {
        @Override
        public void onPhoneAccountSelected(PhoneAccountHandle selectedAccountHandle,
                boolean setDefault) {
            InCallPresenter.getInstance().handleAccountSelection(selectedAccountHandle,
                    setDefault);
        }
        @Override
        public void onDialogDismissed() {
            InCallPresenter.getInstance().cancelAccountSelection();
        }
    };

    /** Listener for orientation changes. */
    private OrientationEventListener mOrientationEventListener;

    /**
     * Used to determine if a change in rotation has occurred.
     */
    private static int sPreviousRotation = -1;

    @Override
    protected void onCreate(Bundle icicle) {
        Log.d(this, "onCreate()...  this = " + this);

        super.onCreate(icicle);

        // set this flag so this activity will stay in front of the keyguard
        // Have the WindowManager filter out touch events that are "too fat".
        int flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_IGNORE_CHEEK_PRESSES;

        getWindow().addFlags(flags);

        // Setup action bar for the conference call manager.
        requestWindowFeature(Window.FEATURE_ACTION_BAR_OVERLAY);
        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setDisplayShowTitleEnabled(true);
            actionBar.hide();
        }

        // TODO(klp): Do we need to add this back when prox sensor is not available?
        // lp.inputFeatures |= WindowManager.LayoutParams.INPUT_FEATURE_DISABLE_USER_ACTIVITY;

        setContentView(R.layout.incall_screen);

        internalResolveIntent(getIntent());

        mIsLandscape = getResources().getConfiguration().orientation ==
                Configuration.ORIENTATION_LANDSCAPE;

        final boolean isRtl = TextUtils.getLayoutDirectionFromLocale(Locale.getDefault()) ==
                View.LAYOUT_DIRECTION_RTL;

        if (mIsLandscape) {
            mSlideIn = AnimationUtils.loadAnimation(this,
                    isRtl ? R.anim.dialpad_slide_in_left : R.anim.dialpad_slide_in_right);
            mSlideOut = AnimationUtils.loadAnimation(this,
                    isRtl ? R.anim.dialpad_slide_out_left : R.anim.dialpad_slide_out_right);
        } else {
            mSlideIn = AnimationUtils.loadAnimation(this, R.anim.dialpad_slide_in_bottom);
            mSlideOut = AnimationUtils.loadAnimation(this, R.anim.dialpad_slide_out_bottom);
        }

        mSlideIn.setInterpolator(AnimUtils.EASE_IN);
        mSlideOut.setInterpolator(AnimUtils.EASE_OUT);

        mSlideOut.setAnimationListener(mSlideOutListener);

        if (icicle != null) {
            // If the dialpad was shown before, set variables indicating it should be shown and
            // populated with the previous DTMF text.  The dialpad is actually shown and populated
            // in onResume() to ensure the hosting CallCardFragment has been inflated and is ready
            // to receive it.
            mShowDialpadRequested = icicle.getBoolean(SHOW_DIALPAD_EXTRA);
            mAnimateDialpadOnShow = false;
            mDtmfText = icicle.getString(DIALPAD_TEXT_EXTRA);

            SelectPhoneAccountDialogFragment dialogFragment = (SelectPhoneAccountDialogFragment)
                getFragmentManager().findFragmentByTag(TAG_SELECT_ACCT_FRAGMENT);
            if (dialogFragment != null) {
                dialogFragment.setListener(mSelectAcctListener);
            }
        }

        mOrientationEventListener = new OrientationEventListener(this,
                SensorManager.SENSOR_DELAY_NORMAL) {
            @Override
            public void onOrientationChanged(int orientation) {
                // Device is flat, don't change orientation.
                if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) {
                    return;
                }

                int newRotation;
                // We only shift if we're within 22.5 (23) degrees of the target
                // orientation. This avoids flopping back and forth when holding
                // the device at 45 degrees or so.
                if (orientation >= 337 || orientation <= 23) {
                    newRotation = Surface.ROTATION_0;
                } else if (orientation >= 67 && orientation <= 113) {
                    // Why not 90? Because screen and sensor orientation are
                    // reversed.
                    newRotation = Surface.ROTATION_270;
                } else if (orientation >= 157 && orientation <= 203) {
                    newRotation = Surface.ROTATION_180;
                } else if (orientation >= 247 && orientation <= 293) {
                    newRotation = Surface.ROTATION_90;
                } else {
                    // Device is between orientations, so leave orientation the same.
                    return;
                }

                // Orientation is the current device orientation in degrees.  Ultimately we want
                // the rotation (in fixed 90 degree intervals).
                if (newRotation != sPreviousRotation) {
                    doOrientationChanged(newRotation);
                }
            }
        };

        if (mOrientationEventListener.canDetectOrientation()) {
            Log.v(this, "Orientation detection enabled.");
            mOrientationEventListener.enable();
        } else {
            Log.v(this, "Orientation detection disabled.");
            mOrientationEventListener.disable();
        }
        Log.d(this, "onCreate(): exit");
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        // TODO: The dialpad fragment should handle this as part of its own state
        out.putBoolean(SHOW_DIALPAD_EXTRA,
                mCallButtonFragment != null && mCallButtonFragment.isDialpadVisible());
        if (mDialpadFragment != null) {
            out.putString(DIALPAD_TEXT_EXTRA, mDialpadFragment.getDtmfText());
        }
        super.onSaveInstanceState(out);
    }

    @Override
    protected void onStart() {
        Log.d(this, "onStart()...");
        super.onStart();

        // setting activity should be last thing in setup process
        InCallPresenter.getInstance().setActivity(this);

        InCallPresenter.getInstance().onActivityStarted();
    }

    @Override
    protected void onResume() {
        Log.i(this, "onResume()...");
        super.onResume();

        mIsForegroundActivity = true;

        InCallPresenter.getInstance().setThemeColors();
        InCallPresenter.getInstance().onUiShowing(true);

        if (mShowDialpadRequested) {
            mCallButtonFragment.displayDialpad(true /* show */,
                    mAnimateDialpadOnShow /* animate */);
            mShowDialpadRequested = false;
            mAnimateDialpadOnShow = false;

            if (mDialpadFragment != null) {
                mDialpadFragment.setDtmfText(mDtmfText);
                mDtmfText = null;
            }
        }

        if (mShowPostCharWaitDialogOnResume) {
            showPostCharWaitDialog(mShowPostCharWaitDialogCallId, mShowPostCharWaitDialogChars);
        }
    }

    // onPause is guaranteed to be called when the InCallActivity goes
    // in the background.
    @Override
    protected void onPause() {
        Log.d(this, "onPause()...");
        super.onPause();

        mIsForegroundActivity = false;

        if (mDialpadFragment != null ) {
            mDialpadFragment.onDialerKeyUp(null);
        }

        InCallPresenter.getInstance().onUiShowing(false);
        if (isFinishing()) {
            InCallPresenter.getInstance().unsetActivity(this);
        }
    }

    @Override
    protected void onStop() {
        Log.d(this, "onStop()...");

        InCallPresenter.getInstance().updateIsChangingConfigurations();
        InCallPresenter.getInstance().onActivityStopped();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        Log.d(this, "onDestroy()...  this = " + this);
        InCallPresenter.getInstance().unsetActivity(this);
        InCallPresenter.getInstance().updateIsChangingConfigurations();
        super.onDestroy();
    }

    /**
     * When fragments have a parent fragment, onAttachFragment is not called on the parent
     * activity. To fix this, register our own callback instead that is always called for
     * all fragments.
     *
     * @see {@link BaseFragment#onAttach(Activity)}
     */
    @Override
    public void onFragmentAttached(Fragment fragment) {
        if (fragment instanceof DialpadFragment) {
            mDialpadFragment = (DialpadFragment) fragment;
        } else if (fragment instanceof AnswerFragment) {
            mAnswerFragment = (AnswerFragment) fragment;
        } else if (fragment instanceof CallCardFragment) {
            mCallCardFragment = (CallCardFragment) fragment;
            mChildFragmentManager = mCallCardFragment.getChildFragmentManager();
        } else if (fragment instanceof ConferenceManagerFragment) {
            mConferenceManagerFragment = (ConferenceManagerFragment) fragment;
        } else if (fragment instanceof CallButtonFragment) {
            mCallButtonFragment = (CallButtonFragment) fragment;
        }
    }

    /**
     * Returns true when theActivity is in foreground (between onResume and onPause).
     */
    /* package */ boolean isForegroundActivity() {
        return mIsForegroundActivity;
    }

    private boolean hasPendingDialogs() {
        return mDialog != null || (mAnswerFragment != null && mAnswerFragment.hasPendingDialogs());
    }

    @Override
    public void finish() {
        Log.i(this, "finish().  Dialog showing: " + (mDialog != null));

        // skip finish if we are still showing a dialog.
        if (!hasPendingDialogs()) {
            super.finish();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        Log.d(this, "onNewIntent: intent = " + intent);

        // We're being re-launched with a new Intent.  Since it's possible for a
        // single InCallActivity instance to persist indefinitely (even if we
        // finish() ourselves), this sequence can potentially happen any time
        // the InCallActivity needs to be displayed.

        // Stash away the new intent so that we can get it in the future
        // by calling getIntent().  (Otherwise getIntent() will return the
        // original Intent from when we first got created!)
        setIntent(intent);

        // Activities are always paused before receiving a new intent, so
        // we can count on our onResume() method being called next.

        // Just like in onCreate(), handle the intent.
        internalResolveIntent(intent);
    }

    @Override
    public void onBackPressed() {
        Log.i(this, "onBackPressed");

        // BACK is also used to exit out of any "special modes" of the
        // in-call UI:

        if ((mConferenceManagerFragment == null || !mConferenceManagerFragment.isVisible())
                && (mCallCardFragment == null || !mCallCardFragment.isVisible())) {
            return;
        }

        if (mDialpadFragment != null && mDialpadFragment.isVisible()) {
            mCallButtonFragment.displayDialpad(false /* show */, true /* animate */);
            return;
        } else if (mConferenceManagerFragment != null && mConferenceManagerFragment.isVisible()) {
            showConferenceFragment(false);
            return;
        }

        // Always disable the Back key while an incoming call is ringing
        final Call call = CallList.getInstance().getIncomingCall();
        if (call != null) {
            Log.i(this, "Consume Back press for an incoming call");
            return;
        }

        // Nothing special to do.  Fall back to the default behavior.
        super.onBackPressed();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final int itemId = item.getItemId();
        if (itemId == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        // push input to the dialer.
        if (mDialpadFragment != null && (mDialpadFragment.isVisible()) &&
                (mDialpadFragment.onDialerKeyUp(event))){
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_CALL) {
            // Always consume CALL to be sure the PhoneWindow won't do anything with it
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_CALL:
                boolean handled = InCallPresenter.getInstance().handleCallKey();
                if (!handled) {
                    Log.w(this, "InCallActivity should always handle KEYCODE_CALL in onKeyDown");
                }
                // Always consume CALL to be sure the PhoneWindow won't do anything with it
                return true;

            // Note there's no KeyEvent.KEYCODE_ENDCALL case here.
            // The standard system-wide handling of the ENDCALL key
            // (see PhoneWindowManager's handling of KEYCODE_ENDCALL)
            // already implements exactly what the UI spec wants,
            // namely (1) "hang up" if there's a current active call,
            // or (2) "don't answer" if there's a current ringing call.

            case KeyEvent.KEYCODE_CAMERA:
                // Disable the CAMERA button while in-call since it's too
                // easy to press accidentally.
                return true;

            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                // Ringer silencing handled by PhoneWindowManager.
                break;

            case KeyEvent.KEYCODE_MUTE:
                // toggle mute
                TelecomAdapter.getInstance().mute(!AudioModeProvider.getInstance().getMute());
                return true;

            // Various testing/debugging features, enabled ONLY when VERBOSE == true.
            case KeyEvent.KEYCODE_SLASH:
                if (Log.VERBOSE) {
                    Log.v(this, "----------- InCallActivity View dump --------------");
                    // Dump starting from the top-level view of the entire activity:
                    Window w = this.getWindow();
                    View decorView = w.getDecorView();
                    Log.d(this, "View dump:" + decorView);
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_EQUALS:
                // TODO: Dump phone state?
                break;
        }

        if (event.getRepeatCount() == 0 && handleDialerKeyDown(keyCode, event)) {
            return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    private boolean handleDialerKeyDown(int keyCode, KeyEvent event) {
        Log.v(this, "handleDialerKeyDown: keyCode " + keyCode + ", event " + event + "...");

        // As soon as the user starts typing valid dialable keys on the
        // keyboard (presumably to type DTMF tones) we start passing the
        // key events to the DTMFDialer's onDialerKeyDown.
        if (mDialpadFragment != null && mDialpadFragment.isVisible()) {
            return mDialpadFragment.onDialerKeyDown(event);
        }

        return false;
    }

    /**
     * Handles changes in device rotation.
     *
     * @param rotation The new device rotation (one of: {@link Surface#ROTATION_0},
     *      {@link Surface#ROTATION_90}, {@link Surface#ROTATION_180},
     *      {@link Surface#ROTATION_270}).
     */
    private void doOrientationChanged(int rotation) {
        Log.d(this, "doOrientationChanged prevOrientation=" + sPreviousRotation +
                " newOrientation=" + rotation);
        // Check to see if the rotation changed to prevent triggering rotation change events
        // for other configuration changes.
        if (rotation != sPreviousRotation) {
            sPreviousRotation = rotation;
            InCallPresenter.getInstance().onDeviceRotationChange(rotation);
            InCallPresenter.getInstance().onDeviceOrientationChange(sPreviousRotation);
        }
    }

    public CallButtonFragment getCallButtonFragment() {
        return mCallButtonFragment;
    }

    public CallCardFragment getCallCardFragment() {
        return mCallCardFragment;
    }

    public AnswerFragment getAnswerFragment() {
        return mAnswerFragment;
    }

    private void internalResolveIntent(Intent intent) {
        final String action = intent.getAction();
        if (action.equals(Intent.ACTION_MAIN)) {
            // This action is the normal way to bring up the in-call UI.
            //
            // But we do check here for one extra that can come along with the
            // ACTION_MAIN intent:

            if (intent.hasExtra(SHOW_DIALPAD_EXTRA)) {
                // SHOW_DIALPAD_EXTRA can be used here to specify whether the DTMF
                // dialpad should be initially visible.  If the extra isn't
                // present at all, we just leave the dialpad in its previous state.

                final boolean showDialpad = intent.getBooleanExtra(SHOW_DIALPAD_EXTRA, false);
                Log.d(this, "- internalResolveIntent: SHOW_DIALPAD_EXTRA: " + showDialpad);

                relaunchedFromDialer(showDialpad);
            }

            boolean newOutgoingCall = false;
            if (intent.getBooleanExtra(NEW_OUTGOING_CALL_EXTRA, false)) {
                intent.removeExtra(NEW_OUTGOING_CALL_EXTRA);
                Call call = CallList.getInstance().getOutgoingCall();
                if (call == null) {
                    call = CallList.getInstance().getPendingOutgoingCall();
                }

                Bundle extras = null;
                if (call != null) {
                    extras = call.getTelecommCall().getDetails().getExtras();
                }
                if (extras == null) {
                    // Initialize the extras bundle to avoid NPE
                    extras = new Bundle();
                }

                Point touchPoint = null;
                if (TouchPointManager.getInstance().hasValidPoint()) {
                    // Use the most immediate touch point in the InCallUi if available
                    touchPoint = TouchPointManager.getInstance().getPoint();
                } else {
                    // Otherwise retrieve the touch point from the call intent
                    if (call != null) {
                        touchPoint = (Point) extras.getParcelable(TouchPointManager.TOUCH_POINT);
                    }
                }

                // Start animation for new outgoing call
                CircularRevealFragment.startCircularReveal(getFragmentManager(), touchPoint,
                        InCallPresenter.getInstance());

                // InCallActivity is responsible for disconnecting a new outgoing call if there
                // is no way of making it (i.e. no valid call capable accounts)
                if (InCallPresenter.isCallWithNoValidAccounts(call)) {
                    TelecomAdapter.getInstance().disconnectCall(call.getId());
                }

                dismissKeyguard(true);
                newOutgoingCall = true;
            }

            Call pendingAccountSelectionCall = CallList.getInstance().getWaitingForAccountCall();
            if (pendingAccountSelectionCall != null) {
                showCallCardFragment(false);
                Bundle extras = pendingAccountSelectionCall
                        .getTelecommCall().getDetails().getExtras();

                final List<PhoneAccountHandle> phoneAccountHandles;
                if (extras != null) {
                    phoneAccountHandles = extras.getParcelableArrayList(
                            android.telecom.Call.AVAILABLE_PHONE_ACCOUNTS);
                } else {
                    phoneAccountHandles = new ArrayList<>();
                }

                DialogFragment dialogFragment = SelectPhoneAccountDialogFragment.newInstance(
                        R.string.select_phone_account_for_calls, true, phoneAccountHandles,
                        mSelectAcctListener);
                dialogFragment.show(getFragmentManager(), TAG_SELECT_ACCT_FRAGMENT);
            } else if (!newOutgoingCall) {
                showCallCardFragment(true);
            }

            return;
        }
    }

    private void relaunchedFromDialer(boolean showDialpad) {
        mShowDialpadRequested = showDialpad;
        mAnimateDialpadOnShow = true;

        if (mShowDialpadRequested) {
            // If there's only one line in use, AND it's on hold, then we're sure the user
            // wants to use the dialpad toward the exact line, so un-hold the holding line.
            final Call call = CallList.getInstance().getActiveOrBackgroundCall();
            if (call != null && call.getState() == State.ONHOLD) {
                TelecomAdapter.getInstance().unholdCall(call.getId());
            }
        }
    }

    public void dismissKeyguard(boolean dismiss) {
        if (mDismissKeyguard == dismiss) {
            return;
        }
        mDismissKeyguard = dismiss;
        if (dismiss) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        }
    }

    private void showFragment(String tag, boolean show, boolean executeImmediately) {
        Trace.beginSection("showFragment - " + tag);
        final FragmentManager fm = getFragmentManagerForTag(tag);

        if (fm == null) {
            Log.w(TAG, "Fragment manager is null for : " + tag);
            return;
        }

        Fragment fragment = fm.findFragmentByTag(tag);
        if (!show && fragment == null) {
            // Nothing to show, so bail early.
            return;
        }

        final FragmentTransaction transaction = fm.beginTransaction();
        if (show) {
            if (fragment == null) {
                fragment = createNewFragmentForTag(tag);
                transaction.add(getContainerIdForFragment(tag), fragment, tag);
            } else {
                transaction.show(fragment);
            }
        } else {
            transaction.hide(fragment);
        }

        transaction.commitAllowingStateLoss();
        if (executeImmediately) {
            fm.executePendingTransactions();
        }
        Trace.endSection();
    }

    private Fragment createNewFragmentForTag(String tag) {
        if (TAG_DIALPAD_FRAGMENT.equals(tag)) {
            mDialpadFragment = new DialpadFragment();
            return mDialpadFragment;
        } else if (TAG_ANSWER_FRAGMENT.equals(tag)) {
            mAnswerFragment = new AnswerFragment();
            return mAnswerFragment;
        } else if (TAG_CONFERENCE_FRAGMENT.equals(tag)) {
            mConferenceManagerFragment = new ConferenceManagerFragment();
            return mConferenceManagerFragment;
        } else if (TAG_CALLCARD_FRAGMENT.equals(tag)) {
            mCallCardFragment = new CallCardFragment();
            return mCallCardFragment;
        }
        throw new IllegalStateException("Unexpected fragment: " + tag);
    }

    private FragmentManager getFragmentManagerForTag(String tag) {
        if (TAG_DIALPAD_FRAGMENT.equals(tag)) {
            return mChildFragmentManager;
        } else if (TAG_ANSWER_FRAGMENT.equals(tag)) {
            return mChildFragmentManager;
        } else if (TAG_CONFERENCE_FRAGMENT.equals(tag)) {
            return getFragmentManager();
        } else if (TAG_CALLCARD_FRAGMENT.equals(tag)) {
            return getFragmentManager();
        }
        throw new IllegalStateException("Unexpected fragment: " + tag);
    }

    private int getContainerIdForFragment(String tag) {
        if (TAG_DIALPAD_FRAGMENT.equals(tag)) {
            return R.id.answer_and_dialpad_container;
        } else if (TAG_ANSWER_FRAGMENT.equals(tag)) {
            return R.id.answer_and_dialpad_container;
        } else if (TAG_CONFERENCE_FRAGMENT.equals(tag)) {
            return R.id.main;
        } else if (TAG_CALLCARD_FRAGMENT.equals(tag)) {
            return R.id.main;
        }
        throw new IllegalStateException("Unexpected fragment: " + tag);
    }

    public void showDialpadFragment(boolean show, boolean animate) {
        // If the dialpad is already visible, don't animate in. If it's gone, don't animate out.
        if ((show && isDialpadVisible()) || (!show && !isDialpadVisible())) {
            return;
        }
        // We don't do a FragmentTransaction on the hide case because it will be dealt with when
        // the listener is fired after an animation finishes.
        if (!animate) {
            showFragment(TAG_DIALPAD_FRAGMENT, show, true);
        } else {
            if (show) {
                showFragment(TAG_DIALPAD_FRAGMENT, true, true);
                mDialpadFragment.animateShowDialpad();
            }
            mCallCardFragment.onDialpadVisibilityChange(show);
            mDialpadFragment.getView().startAnimation(show ? mSlideIn : mSlideOut);
        }

        final ProximitySensor sensor = InCallPresenter.getInstance().getProximitySensor();
        if (sensor != null) {
            sensor.onDialpadVisible(show);
        }
    }

    public boolean isDialpadVisible() {
        return mDialpadFragment != null && mDialpadFragment.isVisible();
    }

    public void showCallCardFragment(boolean show) {
        showFragment(TAG_CALLCARD_FRAGMENT, show, true);
    }

    /**
     * Hides or shows the conference manager fragment.
     *
     * @param show {@code true} if the conference manager should be shown, {@code false} if it
     *                         should be hidden.
     */
    public void showConferenceFragment(boolean show) {
        showFragment(TAG_CONFERENCE_FRAGMENT, show, true);
        mConferenceManagerFragment.onVisibilityChanged(show);

        // Need to hide the call card fragment to ensure that accessibility service does not try to
        // give focus to the call card when the conference manager is visible.
        mCallCardFragment.getView().setVisibility(show ? View.GONE : View.VISIBLE);
    }

    public void showAnswerFragment(boolean show) {
        showFragment(TAG_ANSWER_FRAGMENT, show, true);
    }

    public void showPostCharWaitDialog(String callId, String chars) {
        if (isForegroundActivity()) {
            final PostCharDialogFragment fragment = new PostCharDialogFragment(callId,  chars);
            fragment.show(getFragmentManager(), "postCharWait");

            mShowPostCharWaitDialogOnResume = false;
            mShowPostCharWaitDialogCallId = null;
            mShowPostCharWaitDialogChars = null;
        } else {
            mShowPostCharWaitDialogOnResume = true;
            mShowPostCharWaitDialogCallId = callId;
            mShowPostCharWaitDialogChars = chars;
        }
    }

    @Override
    public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
        if (mCallCardFragment != null) {
            mCallCardFragment.dispatchPopulateAccessibilityEvent(event);
        }
        return super.dispatchPopulateAccessibilityEvent(event);
    }

    public void maybeShowErrorDialogOnDisconnect(DisconnectCause disconnectCause) {
        Log.d(this, "maybeShowErrorDialogOnDisconnect");

        if (!isFinishing() && !TextUtils.isEmpty(disconnectCause.getDescription())
                && (disconnectCause.getCode() == DisconnectCause.ERROR ||
                        disconnectCause.getCode() == DisconnectCause.RESTRICTED)) {
            showErrorDialog(disconnectCause.getDescription());
        }
    }

    public void dismissPendingDialogs() {
        if (mDialog != null) {
            mDialog.dismiss();
            mDialog = null;
        }
        if (mAnswerFragment != null) {
            mAnswerFragment.dismissPendingDialogs();
        }
    }

    /**
     * Utility function to bring up a generic "error" dialog.
     */
    private void showErrorDialog(CharSequence msg) {
        Log.i(this, "Show Dialog: " + msg);

        dismissPendingDialogs();

        mDialog = new AlertDialog.Builder(this)
                .setMessage(msg)
                .setPositiveButton(android.R.string.ok, new OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        onDialogDismissed();
                    }})
                .setOnCancelListener(new OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialog) {
                        onDialogDismissed();
                    }})
                .create();

        mDialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        mDialog.show();
    }

    private void onDialogDismissed() {
        mDialog = null;
        InCallPresenter.getInstance().onDismissDialog();
    }
}
