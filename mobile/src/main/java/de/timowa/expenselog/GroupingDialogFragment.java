package de.timowa.expenselog;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioButton;
import android.widget.RadioGroup;

import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;

public class GroupingDialogFragment extends DialogFragment implements View.OnClickListener {

    private onGroupingUpdateListener mCallback;

    private static final String ARG_PARAM1 = "param1";
    private static final String ARG_PARAM2 = "param2";

    private int passedGroupingSetting;
    private int recordsTimeMode;

    public interface onGroupingUpdateListener {
        void onGroupingUpdate(int groupingSetting);
    }

    public static GroupingDialogFragment newInstance(int sortSetting, int recordsTimeMode) {
        GroupingDialogFragment fragment = new GroupingDialogFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_PARAM1, sortSetting);
        args.putInt(ARG_PARAM2, recordsTimeMode);
        fragment.setArguments(args);
        return fragment;
    }

    public GroupingDialogFragment() {
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
        View view = inflater.inflate(R.layout.graph_grouping_dialog, container);

        // This makes sure that the container activity has implemented
        // the callback interface. If not, it throws an exception
        try {
            mCallback = (onGroupingUpdateListener) getActivity();
        } catch (ClassCastException e) {
            throw new ClassCastException(requireActivity().toString()
                    + " must implement OnHeadlineSelectedListener");
        }

        // get and set current sort setting
        if (getArguments() != null) {
            if (getArguments().containsKey(ARG_PARAM1))
                passedGroupingSetting = getArguments().getInt(ARG_PARAM1, 0);

            this.recordsTimeMode = getArguments().getInt(ARG_PARAM2, 0);
        }

        // set the current user selected setting, unless not available for the current view mode
        RadioGroup sortingSelectionGroup = view.findViewById(R.id.radioGroup_grouping);
        ((RadioButton) sortingSelectionGroup.getChildAt(passedGroupingSetting)).setChecked(true);

        view.findViewById(R.id.radioButton_default).setOnClickListener(this);
        view.findViewById(R.id.radioButton_by_day).setOnClickListener(this);

        // set listeners or hide views based on the time mode being viewed

        switch (recordsTimeMode){
            case LogTabsFragment.KEY_RECORDS_MODE_ALL:
                view.findViewById(R.id.radioButton_by_year).setOnClickListener(this);
                view.findViewById(R.id.radioButton_by_year).setVisibility(View.VISIBLE);
                view.findViewById(R.id.radioButton_by_month).setOnClickListener(this);
                view.findViewById(R.id.radioButton_by_month).setVisibility(View.VISIBLE);
                view.findViewById(R.id.radioButton_by_week).setOnClickListener(this);
                view.findViewById(R.id.radioButton_by_week).setVisibility(View.VISIBLE);
                break;
            case LogTabsFragment.KEY_RECORDS_MODE_YEAR:
                view.findViewById(R.id.radioButton_by_month).setOnClickListener(this);
                view.findViewById(R.id.radioButton_by_month).setVisibility(View.VISIBLE);
                view.findViewById(R.id.radioButton_by_week).setOnClickListener(this);
                view.findViewById(R.id.radioButton_by_week).setVisibility(View.VISIBLE);
                break;
            case LogTabsFragment.KEY_RECORDS_MODE_MONTH:
                view.findViewById(R.id.radioButton_by_week).setOnClickListener(this);
                view.findViewById(R.id.radioButton_by_week).setVisibility(View.VISIBLE);
                break;
        }

        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
    }

    @Override
    public void onClick(View v) {
        int clickedView = v.getId();
        if (clickedView != passedGroupingSetting) {
            if (clickedView == R.id.radioButton_default) {
                mCallback.onGroupingUpdate(0);
            } else if (clickedView == R.id.radioButton_by_day) {
                mCallback.onGroupingUpdate(1);
            } else if (clickedView == R.id.radioButton_by_week) {
                mCallback.onGroupingUpdate(2);
            } else if (clickedView == R.id.radioButton_by_month) {
                mCallback.onGroupingUpdate(3);
            } else if (clickedView == R.id.radioButton_by_year) {
                mCallback.onGroupingUpdate(4);
            }
        }
        this.dismiss();
    }
}