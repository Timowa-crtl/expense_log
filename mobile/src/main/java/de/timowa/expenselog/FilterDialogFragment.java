package de.timowa.expenselog;

import android.database.Cursor;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.os.BundleCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;

import java.util.ArrayList;

public class FilterDialogFragment extends DialogFragment implements View.OnClickListener {

    onFilterUpdateListener mCallback;

    private static final String ARG_PARAM1 = "param1";
    private static final String ARG_PARAM2 = "param2";
    private TextView filterDetailsTextView = null;
    private final int ARRAY_POS_EXPENSE_INCOME = 0;

    private final int ARRAY_POS_ACCOUNT = 1;
    private final int ARRAY_POS_CATEGORY = 2;

    private ArrayList<ArrayList<Integer>> mFilterArray = new ArrayList<ArrayList<Integer>>();

    private RadioGroup transactionTypeGroup;

    public interface onFilterUpdateListener {
        void onUpdateFilter(ArrayList<ArrayList<Integer>> inputText);
    }

    public static FilterDialogFragment newInstance(ArrayList<ArrayList<Integer>> previousFilter) {
        FilterDialogFragment fragment = new FilterDialogFragment();
        Bundle args = new Bundle();
        args.putSerializable(ARG_PARAM2, previousFilter);
        fragment.setArguments(args);
        return fragment;
    }

    public FilterDialogFragment() {
        // Empty constructor required for DialogFragment
    }

    @SuppressWarnings("unchecked")
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.records_filter_dialog, container);

        transactionTypeGroup = (RadioGroup) view.findViewById(R.id.radioGroup_expenseIncome);

        if (getArguments() != null) {
            // Only overwrite the empty default when a filter was actually passed. newInstance(null)
            // is the ordinary "no filter set" case, and assigning its null here left mFilterArray
            // null for updateFilterDetails() below, which crashed the dialog the moment it opened.
            @SuppressWarnings("unchecked")
            ArrayList<ArrayList<Integer>> passed =
                    BundleCompat.getSerializable(getArguments(), ARG_PARAM2, ArrayList.class);
            if (passed != null) mFilterArray = passed;
        }

        filterDetailsTextView = (TextView) view.findViewById(R.id.textView_currentFilterDetails);
        updateFilterDetails();

        // This makes sure that the container activity has implemented
        // the callback interface. If not, it throws an exception
        try {
            mCallback = (onFilterUpdateListener) getActivity();
        } catch (ClassCastException e) {
            throw new ClassCastException(getActivity().toString()
                    + " must implement OnHeadlineSelectedListener");
        }

        // One array list per filter group: expense/income/all, account ids, category ids. Only the
        // ones a passed filter does not already carry -- adding three unconditionally appended to
        // the filter that was handed in, and since MainActivity.mFilterArray is the same list every
        // time, it grew by three empty groups on every visit to this dialog.
        while (mFilterArray.size() < 3) {
            mFilterArray.add(new ArrayList<Integer>());
        }

        // setup transaction selection radio group
        setupTransactionRadioGroup(view);

        // populate accounts list
        populateFilterListForGroup(view, inflater, R.id.linearLayout_accountsContainer, R.id.textView_accountFilterLabel);
        // populate expenses list
        populateFilterListForGroup(view, inflater, R.id.linearLayout_expensesContainer, R.id.textView_expenseFilterLabel);
        // populate incomes list
        populateFilterListForGroup(view, inflater, R.id.linearLayout_incomesContainer, R.id.textView_incomeFilterLabel);

        view.findViewById(R.id.button_dialog_cancel_filter_change).setOnClickListener(this);
        view.findViewById(R.id.button_dialog_filter).setOnClickListener(this);
        view.findViewById(R.id.button_dialog_clear_filter).setOnClickListener(this);

        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
//        getDialog().setTitle(getActivity().getResources().getString(R.string.filter));
        getDialog().getWindow().setTitle(getActivity().getResources().getString(R.string.filter));
    }

    private void setupTransactionRadioGroup(View view) {
        // set click listener for changes to group
        final LinearLayout expensesLayout = (LinearLayout) view.findViewById(R.id.linearLayout_expenses);
        final LinearLayout incomesLayout = (LinearLayout) view.findViewById(R.id.linearLayout_incomes);
        transactionTypeGroup.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                if (checkedId == R.id.radioButton_all) {
                        // if all records show both expense and income groups
                        // remove first item from arraylist
                        mFilterArray.get(0).clear();

                        // show both expense and income lists
                        expensesLayout.setVisibility(View.VISIBLE);
                        incomesLayout.setVisibility(View.VISIBLE);
                } else if (checkedId == R.id.radioButton_expense) {
                        // if only expenses hide income group, and vice versa
                        // add first item to arrayList and set to 0 or 1
                        mFilterArray.get(0).clear();
                        mFilterArray.get(0).add(0);
                        expensesLayout.setVisibility(View.VISIBLE);
                        incomesLayout.setVisibility(View.GONE);
                } else if (checkedId == R.id.radioButton_income) {
                        mFilterArray.get(0).clear();
                        mFilterArray.get(0).add(0, 1);
                        expensesLayout.setVisibility(View.GONE);
                        incomesLayout.setVisibility(View.VISIBLE);
                }
                updateFilterDetails();
            }
        });

        // if a previous option is selected, set it
        if (mFilterArray.get(0).size() > 0) {
            if (mFilterArray.get(0).get(0) == 0)
                ((RadioButton) transactionTypeGroup.getChildAt(1)).setChecked(true);
            else
                ((RadioButton) transactionTypeGroup.getChildAt(2)).setChecked(true);
        }

    }

    private void populateFilterListForGroup(View view, LayoutInflater inflater, int layoutContainer, int filterLabel) {
        final ViewGroup tagsContainer = (ViewGroup) view.findViewById(layoutContainer);
        final TextView tagTypeLabel = (TextView) view.findViewById(filterLabel);

        tagsContainer.setVisibility(View.GONE);
        tagTypeLabel.setCompoundDrawablesWithIntrinsicBounds(
                null, null, ContextCompat.getDrawable(getActivity(), R.drawable.ic_action_arrow_down), null);
        tagTypeLabel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View arg0) {
                // on tag type click hide or show tags for that type and change the drawable
                if (arg0.isActivated()) {
                    arg0.setActivated(false);
                    tagTypeLabel.setCompoundDrawablesWithIntrinsicBounds(
                            null, null, ContextCompat.getDrawable(getActivity(), R.drawable.ic_action_arrow_down), null);
                    tagsContainer.setVisibility(View.GONE);
                } else {
                    arg0.setActivated(true);
                    tagTypeLabel.setCompoundDrawablesWithIntrinsicBounds(
                            null, null, ContextCompat.getDrawable(getActivity(), R.drawable.ic_action_arrow_up), null);
                    tagsContainer.setVisibility(View.VISIBLE);
                }
            }
        });

        DBAdapter db = new DBAdapter(getActivity());
        db.open();

        if (layoutContainer == R.id.linearLayout_accountsContainer) {
                // get list of tags for the given type
                Cursor tagTypeCursor = db.getAccounts();
                populateItemList(inflater, ARRAY_POS_ACCOUNT, tagsContainer, tagTypeCursor, DBAdapter.COLUMN_ACCOUNT_LABEL, DBAdapter.COLUMN_ACCOUNT_ID);
                tagTypeCursor.close();
        } else if (layoutContainer == R.id.linearLayout_expensesContainer) {
                // get list of tags for the given type
                Cursor expenseCursor = db.getTagsOfType(false);
                populateItemList(inflater, ARRAY_POS_CATEGORY, tagsContainer, expenseCursor, DBAdapter.COLUMN_TAG_LABEL, DBAdapter.COLUMN_TAG_ID);
                expenseCursor.close();
        } else if (layoutContainer == R.id.linearLayout_incomesContainer) {
                // get list of tags for the given type
                Cursor incomeCursor = db.getTagsOfType(true);
                populateItemList(inflater, ARRAY_POS_CATEGORY, tagsContainer, incomeCursor, DBAdapter.COLUMN_TAG_LABEL, DBAdapter.COLUMN_TAG_ID);
                incomeCursor.close();
        }
        db.close();
    }

    private void populateItemList(LayoutInflater inflater, final int filterArrayPosition, ViewGroup tagsContainer, Cursor tagListCursor, int labelColumn, int idColumn) {
        while (tagListCursor.moveToNext()) {
            // for each tag
            LinearLayout tagCheckboxLayout =
                    (LinearLayout) inflater.inflate(R.layout.records_filter_dialog_list_item, tagsContainer);
            CheckBox tagCheckBox = (CheckBox) tagCheckboxLayout.findViewById(R.id.checkBox_tagFilter);
            tagCheckBox.setText(tagListCursor.getString(labelColumn));
            tagCheckBox.setId(tagListCursor.getInt(idColumn));

            // if the current tag exists in the array set it to checked
            if (mFilterArray.get(filterArrayPosition).contains(tagListCursor.getInt(idColumn))) {
                tagCheckBox.setChecked(true);
            }
            tagCheckBox.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    CheckBox checkBox = (CheckBox) view;
                    int tagId = checkBox.getId();
                    if (checkBox.isChecked()) {
                        // when a checkbox is clicked add it's id the the arraylist for the of the tagtype
                        if (!mFilterArray.get(filterArrayPosition).contains(tagId)) {
                            if (BuildConfig.DEBUG) Log.i("FTEST", "tagId: " + tagId);

                            mFilterArray.get(filterArrayPosition).add(tagId);
                        }
                    } else {
                        if (mFilterArray.get(filterArrayPosition).contains(tagId)) {
                            mFilterArray.get(filterArrayPosition).remove(mFilterArray.get(filterArrayPosition).lastIndexOf(tagId));
                        }
                    }

                    // update current filter details text
                    updateFilterDetails();
                }
            });
        }
    }


    @Override
    public void onClick(View v) {
        int clickedView = v.getId();
        if (clickedView == R.id.button_dialog_cancel_filter_change) {
            this.dismiss();
        } else if (clickedView == R.id.button_dialog_clear_filter) {
            mFilterArray = null;
            mCallback.onUpdateFilter(null);
            this.dismiss();
        } else if (clickedView == R.id.button_dialog_filter) {
            // create list of selected tags for each tag type
            mCallback.onUpdateFilter(mFilterArray);
            this.dismiss();
        }
    }

    private void updateFilterDetails() {
        DBAdapter db = new DBAdapter(getActivity());
        db.open();
//        String filterDetails = getActivity().getResources().getString(R.string.filters) + ":";
        String filterDetails = "";

        // check if limited to only expenses or only incomes
        if (((RadioButton) transactionTypeGroup.getChildAt(1)).isChecked()) {
            // expenses only selected
            filterDetails = getResources().getString(R.string.expenses);
        } else if (((RadioButton) transactionTypeGroup.getChildAt(2)).isChecked()) {
            // incomes only selected
            filterDetails = getResources().getString(R.string.incomes);
        }

        // check arraylist-list for any entries
        if (mFilterArray.size() > 0) {
            // if any account filters exist
            if (mFilterArray.get(ARRAY_POS_ACCOUNT).size() > 0) {
                // add a line break if a filter exists
                if (!filterDetails.equals(""))
                    filterDetails += "\n";

                ArrayList<Integer> listForType = mFilterArray.get(ARRAY_POS_ACCOUNT);

                filterDetails += getActivity().getResources().getString(R.string.accounts) + ":";

                for (int i = 0; i < listForType.size(); i++) {
                    // for each account filter add it to the string
                    filterDetails += " " + db.getAccountLabel(listForType.get(i));

                    // add a comma if not the last item
                    if (i + 1 < listForType.size())
                        filterDetails += ", ";
                }

            }

            // if any category filters exist
            if (mFilterArray.get(ARRAY_POS_CATEGORY).size() > 0) {
                ArrayList<Integer> listForType = mFilterArray.get(ARRAY_POS_CATEGORY);

                // add a line break if a filter exists
                if (!filterDetails.equals(""))
                    filterDetails += "\n";

                // if accounts exist, add a new line before adding categories
                if (mFilterArray.get(ARRAY_POS_CATEGORY).size() > 0)
                    filterDetails += getActivity().getResources().getString(R.string.categories) + ":";

                for (int i = 0; i < listForType.size(); i++) {
                    // for each account filter add it to the string
                    filterDetails += " " + db.getCategoryLabel(listForType.get(i));

                    // add a comma if not the last item
                    if (i + 1 < listForType.size())
                        filterDetails += ", ";
                }
            }
        }

        if (filterDetails.equals("")) {
            // if no filters exist hide view
            filterDetailsTextView.setVisibility(View.GONE);
        } else {
            filterDetailsTextView.setVisibility(View.VISIBLE);
        }
        db.close();
        filterDetailsTextView.setText(filterDetails);
    }

}