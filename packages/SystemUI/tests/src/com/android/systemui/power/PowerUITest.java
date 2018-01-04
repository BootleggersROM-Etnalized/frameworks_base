/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.power;

import static android.os.HardwarePropertiesManager.DEVICE_TEMPERATURE_SKIN;
import static android.os.HardwarePropertiesManager.TEMPERATURE_CURRENT;
import static android.os.HardwarePropertiesManager.TEMPERATURE_SHUTDOWN;
import static android.provider.Settings.Global.SHOW_TEMPERATURE_WARNING;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.os.BatteryManager;
import android.os.HardwarePropertiesManager;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.testing.TestableResources;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.power.PowerUI.WarningsUI;
import com.android.systemui.statusbar.phone.StatusBar;

import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class PowerUITest extends SysuiTestCase {

    private static final boolean UNPLUGGED = false;
    private static final boolean POWER_SAVER_OFF = false;
    private static final int ABOVE_WARNING_BUCKET = 1;
    public static final int BELOW_WARNING_BUCKET = -1;
    public static final long BELOW_HYBRID_THRESHOLD = TimeUnit.HOURS.toMillis(2);
    public static final long ABOVE_HYBRID_THRESHOLD = TimeUnit.HOURS.toMillis(4);
    private HardwarePropertiesManager mHardProps;
    private WarningsUI mMockWarnings;
    private PowerUI mPowerUI;
    private EnhancedEstimates mEnhacedEstimates;

    @Before
    public void setup() {
        mMockWarnings = mDependency.injectMockDependency(WarningsUI.class);
        mEnhacedEstimates = mDependency.injectMockDependency(EnhancedEstimates.class);
        mHardProps = mock(HardwarePropertiesManager.class);

        mContext.putComponent(StatusBar.class, mock(StatusBar.class));
        mContext.addMockSystemService(Context.HARDWARE_PROPERTIES_SERVICE, mHardProps);

        createPowerUi();
    }

    @Test
    public void testNoConfig_NoWarnings() {
        setOverThreshold();
        Settings.Global.putString(mContext.getContentResolver(), SHOW_TEMPERATURE_WARNING, null);
        TestableResources resources = mContext.getOrCreateTestableResources();
        resources.addOverride(R.integer.config_showTemperatureWarning, 0);
        resources.addOverride(R.integer.config_warningTemperature, 55);

        mPowerUI.start();
        verify(mMockWarnings, never()).showHighTemperatureWarning();
    }

    @Test
    public void testConfig_NoWarnings() {
        setUnderThreshold();
        Settings.Global.putString(mContext.getContentResolver(), SHOW_TEMPERATURE_WARNING, null);
        TestableResources resources = mContext.getOrCreateTestableResources();
        resources.addOverride(R.integer.config_showTemperatureWarning, 1);
        resources.addOverride(R.integer.config_warningTemperature, 55);

        mPowerUI.start();
        verify(mMockWarnings, never()).showHighTemperatureWarning();
    }

    @Test
    public void testConfig_Warnings() {
        setOverThreshold();
        Settings.Global.putString(mContext.getContentResolver(), SHOW_TEMPERATURE_WARNING, null);
        TestableResources resources = mContext.getOrCreateTestableResources();
        resources.addOverride(R.integer.config_showTemperatureWarning, 1);
        resources.addOverride(R.integer.config_warningTemperature, 55);

        mPowerUI.start();
        verify(mMockWarnings).showHighTemperatureWarning();
    }

    @Test
    public void testSettingOverrideConfig() {
        setOverThreshold();
        Settings.Global.putInt(mContext.getContentResolver(), SHOW_TEMPERATURE_WARNING, 1);
        TestableResources resources = mContext.getOrCreateTestableResources();
        resources.addOverride(R.integer.config_showTemperatureWarning, 0);
        resources.addOverride(R.integer.config_warningTemperature, 55);

        mPowerUI.start();
        verify(mMockWarnings).showHighTemperatureWarning();
    }

    @Test
    public void testShutdownBasedThreshold() {
        int tolerance = 2;
        Settings.Global.putString(mContext.getContentResolver(), SHOW_TEMPERATURE_WARNING, null);
        TestableResources resources = mContext.getOrCreateTestableResources();
        resources.addOverride(R.integer.config_showTemperatureWarning, 1);
        resources.addOverride(R.integer.config_warningTemperature, -1);
        resources.addOverride(R.integer.config_warningTemperatureTolerance, tolerance);
        when(mHardProps.getDeviceTemperatures(DEVICE_TEMPERATURE_SKIN, TEMPERATURE_SHUTDOWN))
                .thenReturn(new float[] { 55 + tolerance });

        setCurrentTemp(54); // Below threshold.
        mPowerUI.start();
        verify(mMockWarnings, never()).showHighTemperatureWarning();

        setCurrentTemp(56); // Above threshold.
        mPowerUI.updateTemperatureWarning();
        verify(mMockWarnings).showHighTemperatureWarning();
    }

    @Test
    public void testShouldShowLowBatteryWarning_showHybridOnly_returnsShow() {
        when(mEnhacedEstimates.isHybridNotificationEnabled()).thenReturn(true);
        mPowerUI.start();

        // unplugged device that would not show the non-hybrid notification but would show the
        // hybrid
        boolean shouldShow =
                mPowerUI.shouldShowLowBatteryWarning(UNPLUGGED, UNPLUGGED, ABOVE_WARNING_BUCKET,
                        ABOVE_WARNING_BUCKET, Long.MAX_VALUE, BELOW_HYBRID_THRESHOLD,
                        POWER_SAVER_OFF, BatteryManager.BATTERY_HEALTH_GOOD);
        assertTrue(shouldShow);
    }

    @Test
    public void testShouldShowLowBatteryWarning_showHybrid_showStandard_returnsShow() {
        when(mEnhacedEstimates.isHybridNotificationEnabled()).thenReturn(true);
        mPowerUI.start();

        // unplugged device that would show the non-hybrid notification and the hybrid
        boolean shouldShow =
                mPowerUI.shouldShowLowBatteryWarning(UNPLUGGED, UNPLUGGED, ABOVE_WARNING_BUCKET,
                        BELOW_WARNING_BUCKET, Long.MAX_VALUE, BELOW_HYBRID_THRESHOLD,
                        POWER_SAVER_OFF, BatteryManager.BATTERY_HEALTH_GOOD);
        assertTrue(shouldShow);
    }

    @Test
    public void testShouldShowLowBatteryWarning_showStandardOnly_returnsShow() {
        when(mEnhacedEstimates.isHybridNotificationEnabled()).thenReturn(true);
        mPowerUI.start();

        // unplugged device that would show the non-hybrid but not the hybrid
        boolean shouldShow =
                mPowerUI.shouldShowLowBatteryWarning(UNPLUGGED, UNPLUGGED, ABOVE_WARNING_BUCKET,
                        BELOW_WARNING_BUCKET, Long.MAX_VALUE, ABOVE_HYBRID_THRESHOLD,
                        POWER_SAVER_OFF, BatteryManager.BATTERY_HEALTH_GOOD);
        assertTrue(shouldShow);
    }

    @Test
    public void testShouldShowLowBatteryWarning_deviceHighBattery_returnsNoShow() {
        when(mEnhacedEstimates.isHybridNotificationEnabled()).thenReturn(true);
        mPowerUI.start();

        // unplugged device that would show the neither due to battery level being good
        boolean shouldShow =
                mPowerUI.shouldShowLowBatteryWarning(UNPLUGGED, UNPLUGGED, ABOVE_WARNING_BUCKET,
                        ABOVE_WARNING_BUCKET, ABOVE_HYBRID_THRESHOLD, ABOVE_HYBRID_THRESHOLD,
                        POWER_SAVER_OFF, BatteryManager.BATTERY_HEALTH_GOOD);
        assertFalse(shouldShow);
    }

    @Test
    public void testShouldShowLowBatteryWarning_devicePlugged_returnsNoShow() {
        when(mEnhacedEstimates.isHybridNotificationEnabled()).thenReturn(true);
        mPowerUI.start();

        // plugged device that would show the neither due to being plugged
        boolean shouldShow =
                mPowerUI.shouldShowLowBatteryWarning(!UNPLUGGED, UNPLUGGED, ABOVE_WARNING_BUCKET,
                        BELOW_WARNING_BUCKET, ABOVE_HYBRID_THRESHOLD, BELOW_HYBRID_THRESHOLD,
                        POWER_SAVER_OFF, BatteryManager.BATTERY_HEALTH_GOOD);
        assertFalse(shouldShow);
   }

    @Test
    public void testShouldShowLowBatteryWarning_deviceBatteryStatusUnkown_returnsNoShow() {
        when(mEnhacedEstimates.isHybridNotificationEnabled()).thenReturn(true);
        mPowerUI.start();

        // Unknown battery status device that would show the neither due
        boolean shouldShow =
                mPowerUI.shouldShowLowBatteryWarning(UNPLUGGED, UNPLUGGED, ABOVE_WARNING_BUCKET,
                        BELOW_WARNING_BUCKET, ABOVE_HYBRID_THRESHOLD, BELOW_HYBRID_THRESHOLD,
                        !POWER_SAVER_OFF, BatteryManager.BATTERY_STATUS_UNKNOWN);
        assertFalse(shouldShow);
    }

    @Test
    public void testShouldShowLowBatteryWarning_batterySaverEnabled_returnsNoShow() {
        when(mEnhacedEstimates.isHybridNotificationEnabled()).thenReturn(true);
        mPowerUI.start();

        // BatterySaverEnabled device that would show the neither due to battery saver
        boolean shouldShow =
                mPowerUI.shouldShowLowBatteryWarning(UNPLUGGED, UNPLUGGED, ABOVE_WARNING_BUCKET,
                        BELOW_WARNING_BUCKET, ABOVE_HYBRID_THRESHOLD, BELOW_HYBRID_THRESHOLD,
                        !POWER_SAVER_OFF, BatteryManager.BATTERY_HEALTH_GOOD);
        assertFalse(shouldShow);
    }

    @Test
    public void testShouldDismissLowBatteryWarning_dismissWhenPowerSaverEnabled() {
        mPowerUI.start();
        when(mEnhacedEstimates.isHybridNotificationEnabled()).thenReturn(true);
        // device that gets power saver turned on should dismiss
        boolean shouldDismiss =
                mPowerUI.shouldDismissLowBatteryWarning(UNPLUGGED, BELOW_WARNING_BUCKET,
                        BELOW_WARNING_BUCKET, ABOVE_HYBRID_THRESHOLD, !POWER_SAVER_OFF);
        assertTrue(shouldDismiss);
    }

    @Test
    public void testShouldDismissLowBatteryWarning_dismissWhenPlugged() {
        mPowerUI.start();
        when(mEnhacedEstimates.isHybridNotificationEnabled()).thenReturn(true);

        // device that gets plugged in should dismiss
        boolean shouldDismiss =
                mPowerUI.shouldDismissLowBatteryWarning(!UNPLUGGED, BELOW_WARNING_BUCKET,
                        BELOW_WARNING_BUCKET, ABOVE_HYBRID_THRESHOLD, POWER_SAVER_OFF);
        assertTrue(shouldDismiss);
    }

    @Test
    public void testShouldDismissLowBatteryWarning_dismissHybridSignal_showStandardSignal_shouldShow() {
        mPowerUI.start();
        when(mEnhacedEstimates.isHybridNotificationEnabled()).thenReturn(true);
        // would dismiss hybrid but not non-hybrid should not dismiss
        boolean shouldDismiss =
                mPowerUI.shouldDismissLowBatteryWarning(UNPLUGGED, BELOW_WARNING_BUCKET,
                        BELOW_WARNING_BUCKET, ABOVE_HYBRID_THRESHOLD, POWER_SAVER_OFF);
        assertFalse(shouldDismiss);
    }

    @Test
    public void testShouldDismissLowBatteryWarning_showHybridSignal_dismissStandardSignal_shouldShow() {
        mPowerUI.start();
        when(mEnhacedEstimates.isHybridNotificationEnabled()).thenReturn(true);

        // would dismiss non-hybrid but not hybrid should not dismiss
        boolean shouldDismiss =
                mPowerUI.shouldDismissLowBatteryWarning(UNPLUGGED, BELOW_WARNING_BUCKET,
                        ABOVE_WARNING_BUCKET, BELOW_HYBRID_THRESHOLD, POWER_SAVER_OFF);
        assertFalse(shouldDismiss);
    }

    @Test
    public void testShouldDismissLowBatteryWarning_showBothSignal_shouldShow() {
        mPowerUI.start();
        when(mEnhacedEstimates.isHybridNotificationEnabled()).thenReturn(true);

        // should not dismiss when both would not dismiss
        boolean shouldDismiss =
                mPowerUI.shouldDismissLowBatteryWarning(UNPLUGGED, BELOW_WARNING_BUCKET,
                        BELOW_WARNING_BUCKET, BELOW_HYBRID_THRESHOLD, POWER_SAVER_OFF);
        assertFalse(shouldDismiss);
    }

    @Test
    public void testShouldDismissLowBatteryWarning_dismissBothSignal_shouldDismiss() {
        mPowerUI.start();
        when(mEnhacedEstimates.isHybridNotificationEnabled()).thenReturn(true);

        //should dismiss if both would dismiss
        boolean shouldDismiss =
                mPowerUI.shouldDismissLowBatteryWarning(UNPLUGGED, BELOW_WARNING_BUCKET,
                        ABOVE_WARNING_BUCKET, ABOVE_HYBRID_THRESHOLD, POWER_SAVER_OFF);
        assertTrue(shouldDismiss);
    }

    @Test
    public void testShouldDismissLowBatteryWarning_dismissStandardSignal_hybridDisabled_shouldDismiss() {
        mPowerUI.start();
        when(mEnhacedEstimates.isHybridNotificationEnabled()).thenReturn(false);

        // would dismiss non-hybrid with hybrid disabled should dismiss
        boolean shouldDismiss =
                mPowerUI.shouldDismissLowBatteryWarning(UNPLUGGED, BELOW_WARNING_BUCKET,
                        ABOVE_WARNING_BUCKET, ABOVE_HYBRID_THRESHOLD, POWER_SAVER_OFF);
        assertTrue(shouldDismiss);
    }

    private void setCurrentTemp(float temp) {
        when(mHardProps.getDeviceTemperatures(DEVICE_TEMPERATURE_SKIN, TEMPERATURE_CURRENT))
                .thenReturn(new float[] { temp });
    }

    private void setOverThreshold() {
        setCurrentTemp(50000);
    }

    private void setUnderThreshold() {
        setCurrentTemp(5);
    }

    private void createPowerUi() {
        mPowerUI = new PowerUI();
        mPowerUI.mContext = mContext;
        mPowerUI.mComponents = mContext.getComponents();
    }
}
