package de.timowa.expenselog;

import android.app.Dialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import androidx.preference.PreferenceManager;
import android.widget.TimePicker;

import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;

import java.util.Calendar;

public class TimePickerFragment extends DialogFragment implements TimePickerDialog.OnTimeSetListener {
    private TimePickedListener mListener;
    
    @NonNull
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        // use the current time as the default values for the picker
        final Calendar c = Calendar.getInstance();
        int hour = c.get(Calendar.HOUR_OF_DAY);
        int minute = c.get(Calendar.MINUTE);
        if (getArguments() != null) {
            c.setTimeInMillis(getArguments().getLong("time"));
            hour = c.get(Calendar.HOUR_OF_DAY);
            minute = c.get(Calendar.MINUTE);
        }
        // create a new instance of TimePickerDialog and return it
        
        // English AM/PM, like the rest of the app
        return AppLocale.timePicker(requireActivity(), this, hour, minute, get24Format());
//        return new TimePickerDialog(getActivity(), this, hour, minute, true);
    }

    private boolean get24Format() {
        boolean is24 = false;
        SharedPreferences prefs =
                PreferenceManager.getDefaultSharedPreferences(getActivity());
        if (prefs.contains(getActivity().getString(R.string.pref_key_time_format))) {
            String userTimePref = prefs.getString(getActivity().getString(R.string.pref_key_time_format), "0");
            if (userTimePref.equals("0")) {
                is24 = false;
            } else if (userTimePref.equals("1")) {
                is24 = true;
            }
        } else {
            String systemPref = android.provider.Settings.System.getString(getActivity().getContentResolver()
                    , android.provider.Settings.System.TIME_12_24);
            if (systemPref != null) {
                if (systemPref.equals("24")) {
                    is24 = true;
                } else if (systemPref.equals("12")) {
                    is24 = false;
                }
            }
        }
        return is24;
    }

    @Override
    public void onAttach(Context context) {
        // when the fragment is initially shown (i.e. attached to the activity), cast the activity to the callback interface type
        super.onAttach(context);
        try {
            mListener = (TimePickedListener) context;
        } catch (ClassCastException e) {
            throw new ClassCastException(context.toString() + " must implement " + TimePickedListener.class.getName());
        }
    }

    public void onTimeSet(TimePicker view, int hourOfDay, int minute) {
        // when the time is selected, send it to the activity via its callback interface method
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, hourOfDay);
        c.set(Calendar.MINUTE, minute);

        mListener.onTimePicked(c);
    }

    public interface TimePickedListener {
        void onTimePicked(Calendar time);
    }
}