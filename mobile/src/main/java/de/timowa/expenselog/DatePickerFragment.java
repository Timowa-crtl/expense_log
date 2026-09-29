package de.timowa.expenselog;

import android.app.DatePickerDialog;
import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.widget.DatePicker;

import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;

import java.util.Calendar;

public class DatePickerFragment extends DialogFragment implements DatePickerDialog.OnDateSetListener {
    /** The time, in milliseconds, whose date the picker opens on. */
    static final String ARG_DATE = "date";

    private DatePickedListener mListener;

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        // Open on the date the form shows, which the caller passes as "date": this read today's
        // date whatever it was given, so every edit of an old record started from today.
        final Calendar c = Calendar.getInstance();
        if (getArguments() != null && getArguments().containsKey(ARG_DATE))
            c.setTimeInMillis(getArguments().getLong(ARG_DATE));
        int year = c.get(Calendar.YEAR);
        int month = c.get(Calendar.MONTH);
        int day = c.get(Calendar.DAY_OF_MONTH);

// Create a new instance of DatePickerDialog and return it
        // English month and weekday names, like the rest of the app
        return AppLocale.datePicker(requireActivity(), this, year, month, day);
    }

    @Override
    public void onAttach(Context context) {
        // when the fragment is initially shown (i.e. attached to the activity), cast the activity to the callback interface type
        super.onAttach(context);
        try {
            mListener = (DatePickedListener) context;
        } catch (ClassCastException e) {
            throw new ClassCastException(context.toString() + " must implement " + DatePickedListener.class.getName());
        }
    }

    @Override
    public void onDateSet(DatePicker view, int year, int month, int day) {
        Calendar c = Calendar.getInstance();
        c.set(year, month, day);
        mListener.onDatePicked(c);
    }

    public interface DatePickedListener {
        void onDatePicked(Calendar date);
    }
}