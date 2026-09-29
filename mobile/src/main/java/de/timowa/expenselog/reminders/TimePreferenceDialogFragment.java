package de.timowa.expenselog.reminders;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.TimePicker;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceDialogFragmentCompat;

/**
 * The picker behind {@link TimePreference}.
 *
 * <p>AndroidX splits a dialog preference in two: the preference holds the value, a
 * {@link PreferenceDialogFragmentCompat} shows the dialog. {@code RemindersPreferenceFragment}
 * routes to this from {@code onDisplayPreferenceDialog}; without that the framework would show its
 * own dialog for an unknown preference type and the time could not be set at all.
 */
public class TimePreferenceDialogFragment extends PreferenceDialogFragmentCompat {

    private TimePicker picker;

    public static TimePreferenceDialogFragment newInstance(String key) {
        TimePreferenceDialogFragment fragment = new TimePreferenceDialogFragment();
        Bundle args = new Bundle(1);
        args.putString(ARG_KEY, key);
        fragment.setArguments(args);
        return fragment;
    }

    private TimePreference preference() {
        return (TimePreference) getPreference();
    }

    @NonNull
    @Override
    protected View onCreateDialogView(@NonNull Context context) {
        picker = new TimePicker(context);
        return picker;
    }

    @Override
    protected void onBindDialogView(@NonNull View view) {
        super.onBindDialogView(view);
        TimePreference preference = preference();
        picker.setIs24HourView(preference.is24Hour());
        picker.setHour(preference.getHour());
        picker.setMinute(preference.getMinute());
    }

    @Override
    public void onDialogClosed(boolean positiveResult) {
        if (positiveResult) {
            preference().setTime(picker.getHour(), picker.getMinute());
        }
    }
}
