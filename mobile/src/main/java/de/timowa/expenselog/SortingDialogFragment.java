package de.timowa.expenselog;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioButton;
import android.widget.RadioGroup;

import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;

public class SortingDialogFragment extends DialogFragment implements View.OnClickListener {

    private onSortingUpdateListener mCallback;

    private static final String ARG_PARAM1 = "param1";
    private static final String ARG_PARAM2 = "param2";

    private RadioGroup sortingSelectionGroup;
    private int passedSortSetting;

    public interface onSortingUpdateListener {
        void onUpdateSort(int sort);
    }

    public static SortingDialogFragment newInstance(int sortSetting) {
        SortingDialogFragment fragment = new SortingDialogFragment();
        Bundle args = new Bundle();
//        args.putSerializable(ARG_PARAM2, previousFilter);
        args.putInt(ARG_PARAM1, sortSetting);
        fragment.setArguments(args);
        return fragment;
    }

    public SortingDialogFragment() {
        // Empty constructor required for DialogFragment
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NO_TITLE, 0);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.records_sorting_dialog, container);

        // This makes sure that the container activity has implemented
        // the callback interface. If not, it throws an exception
        try {
            mCallback = (onSortingUpdateListener) getActivity();
        } catch (ClassCastException e) {
            throw new ClassCastException(requireActivity().toString()
                    + " must implement OnHeadlineSelectedListener");
        }

        // get and set current sort setting
        if (getArguments() != null) {
            if (getArguments().containsKey(ARG_PARAM1))
                passedSortSetting = getArguments().getInt(ARG_PARAM1, 0);
        }
        sortingSelectionGroup = view.findViewById(R.id.radioGroup_sorting);
        ((RadioButton) sortingSelectionGroup.getChildAt(passedSortSetting)).setChecked(true);

        view.findViewById(R.id.radioButton_date_low).setOnClickListener(this);
        view.findViewById(R.id.radioButton_date_high).setOnClickListener(this);
        view.findViewById(R.id.radioButton_amount_low).setOnClickListener(this);
        view.findViewById(R.id.radioButton_amount_high).setOnClickListener(this);

        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
    }

    @Override
    public void onClick(View v) {
        int clickedView = v.getId();
        if (clickedView != passedSortSetting) {
            if (clickedView == R.id.radioButton_date_low) {
                mCallback.onUpdateSort(0);
            } else if (clickedView == R.id.radioButton_date_high) {
                mCallback.onUpdateSort(1);
            } else if (clickedView == R.id.radioButton_amount_low) {
                mCallback.onUpdateSort(2);
            } else if (clickedView == R.id.radioButton_amount_high) {
                mCallback.onUpdateSort(3);
            }
        }
        this.dismiss();
    }
}