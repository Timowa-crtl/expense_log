package de.timowa.expenselog;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.database.Cursor;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.SwitchCompat;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import de.timowa.expenselog.icons.IconDrawable;
import de.timowa.expenselog.icons.FontAwesomeIcons;
import de.timowa.expenselog.icons.MaterialIcons;

import javax.inject.Inject;

import de.timowa.expenselog.DependencyInjection.ActivityComponent;
import de.timowa.expenselog.databinding.FragmentNewCategoryBinding;

import static androidx.core.content.ContextCompat.getColor;

/**
 * Create or edit a tag
 */
public class NewCategoryFragment extends BaseFragment {

    private FragmentNewCategoryBinding binding;
    // the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
    private static final String ARG_PARAM1 = "param1";
    private static final String ARG_PARAM2 = "param2";
    private static final String CATEGORY_MANAGEMENT_TAG = "Category Management";

    private int passedCategory;
    private boolean fromNewLogFlag = false;

    static int NEW_TAG_KEY = 999999;
    private GridView iconGridView;
    private ImageView selectedImage;
    private EditText newCategoryEditText;

    @Inject
    Navigator navigator;

    @Inject
    Activity activity;

    @Inject
    Utility utility;

    @Inject
    DBAdapter dbAdapter;

    @Inject
    PrefManager prefManager;

    SwitchCompat expenseIncomeSwitch;
    
    /**
     * Use this factory method to create a new instance of
     * this fragment using the provided parameters.
     *
     * @param passedCat      Parameter 1.
     * @param fromNewLogFlag a flag that is set if coming from new log fragment to signal returning to that fragment with the new category
     * @return A new instance of fragment HomeFragment.
     */
    public static NewCategoryFragment newInstance(int passedCat, boolean fromNewLogFlag) {
        NewCategoryFragment fragment = new NewCategoryFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_PARAM1, passedCat);
        args.putBoolean(ARG_PARAM2, fromNewLogFlag);
        fragment.setArguments(args);
        return fragment;
    }

    public NewCategoryFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            passedCategory = getArguments().getInt(ARG_PARAM1);
            fromNewLogFlag = getArguments().getBoolean(ARG_PARAM2);
        }

    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        binding = FragmentNewCategoryBinding.inflate(inflater, container, false);
        View view = binding.getRoot();

        expenseIncomeSwitch = binding.switchExpenseIncome;

        selectedImage = view.findViewById(R.id.imageView_newIcon);
        newCategoryEditText = view.findViewById(R.id.editText_new_category);

        iconGridView = view.findViewById(R.id.gridView_icons);
        final IconListAdapter iconListAdapter = new IconListAdapter(requireActivity());
        iconGridView.setAdapter(iconListAdapter);
        iconGridView.setOnItemClickListener(iconGridListener);

        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        this.initialize();

        setupFab();

        setupExpenseIncomeSwitch();

        navigator.setToolbarTitle(getResources().getString(R.string.new_category));

        // passed category, set previous
        if (passedCategory != NEW_TAG_KEY) {
            Cursor tag = dbAdapter.getTag(passedCategory);
            if (tag != null) {
                tag.moveToFirst();
                IconDrawable icon = new IconDrawable(requireActivity(), tag.getString(DBAdapter.COLUMN_TAG_ICON));
                selectedImage.setImageDrawable(icon);
                selectedImage.setTag(tag.getString(DBAdapter.COLUMN_TAG_ICON));
                newCategoryEditText.setText(tag.getString(DBAdapter.COLUMN_TAG_LABEL));
                if (BuildConfig.DEBUG) Log.i("NewCategoryFragment", "new cat edit");
                navigator.setToolbarTitle(requireActivity().getResources().getString(R.string.edit_category));
                if (tag.getInt(DBAdapter.COLUMN_TAG_TYPE) == 1)
                    expenseIncomeSwitch.setChecked(true);
            } else {
                // set default icon
                IconDrawable icon = new IconDrawable(requireActivity(), FontAwesomeIcons.fa_tag);
                selectedImage.setImageDrawable(icon);
                selectedImage.setTag(FontAwesomeIcons.fa_tag.key());
            }

        } else {
            // set default icon
            IconDrawable icon = new IconDrawable(requireActivity(), FontAwesomeIcons.fa_tag);
            selectedImage.setImageDrawable(icon);
            selectedImage.setTag(FontAwesomeIcons.fa_tag.key());
        }
    }

    GridView.OnItemClickListener iconGridListener = new GridView.OnItemClickListener() {
        @Override
        public void onItemClick(AdapterView<?> parent, View v,
                                final int position, long id) {
            v.setActivated(true);
            String selectedIcon = (String) v.getTag();
            iconGridView.post(new Runnable() {
                @Override
                public void run() {
                    if (BuildConfig.DEBUG)
                        Log.i("NewCategoryFragment", "selected item2: " + iconGridView.getSelectedItemPosition());
                }
            });

            IconDrawable icon = new IconDrawable(requireActivity(), selectedIcon);
            selectedImage.setTag(selectedIcon);
            selectedImage.setImageDrawable(icon);
        }
    };

    private void initialize() {
        this.getComponent(ActivityComponent.class).inject(this);
    }

    private void setupFab() {
        FloatingActionButton fab = this.requireActivity().findViewById(R.id.fab);
        IconDrawable icon = new IconDrawable(requireActivity(), MaterialIcons.md_check);
        icon.color(getColor(requireActivity(), R.color.colorPrimaryLight));
        fab.setImageDrawable(icon);
        // hide and show again to address disappearing icon bug in material 1.0.0
        fab.hide();
        fab.show();
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {

                verifyAndSaveNewTag();
            }
        });
    }

    private boolean verifyAndSaveNewTag() {
        String newTagString = newCategoryEditText.getText().toString().trim();

        int expenseIncomeValue = 0;
        if (expenseIncomeSwitch.isChecked())
            expenseIncomeValue = 1;

        String anaTag = expenseIncomeSwitch.isChecked() ? "Income" : "Expense";

        boolean isTagDuplicate = false;

        // check if tag label is a duplicate if not editing a tag
        if (passedCategory == NEW_TAG_KEY)
            isTagDuplicate = dbAdapter.doesTagExist(newTagString, expenseIncomeValue);

        int newCatId = 0;

        if (!newTagString.equals("") && !isTagDuplicate) {
            if (passedCategory == NEW_TAG_KEY) {
                // add new tag
                newCatId = dbAdapter.newTag(newTagString, expenseIncomeValue, (String) selectedImage.getTag());
                
                
            } else {
                // update a tag
                dbAdapter.updateTag(passedCategory, newTagString, expenseIncomeValue, (String) selectedImage.getTag());
                
            }
            utility.snackBarMessage(newTagString + " " + activity.getString(R.string.saved));

            // hide keyboard after finished
            View view = requireActivity().getCurrentFocus();
            if (view != null) {
                InputMethodManager imm = (InputMethodManager) requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
            }

            // if coming from new log frag, set the new cat as the selected one
            if (fromNewLogFlag) {

                prefManager.setNewTagFromNewLog(newCatId);
            }

            requireActivity().onBackPressed();
            return true;
        } else {
            // animate invalid selection
            animateInvalidEntry();

            if (isTagDuplicate) {
                utility.snackBarMessage(activity.getString(R.string.category_already_exists));

            }
            return false;
        }
    }

    private void animateInvalidEntry() {
        FloatingActionButton fab = this.requireActivity().findViewById(R.id.fab);
        Animation animationScaleUp = AnimationUtils.loadAnimation(requireActivity(), R.anim.shake);
        fab.startAnimation(animationScaleUp);
    }

    private void setupExpenseIncomeSwitch() {
        // set default expense income value
        if (prefManager.getDefaultLogExpenseIncome() == 1)
            expenseIncomeSwitch.setChecked(true);

        final TextView expenseTextView = requireActivity().findViewById(R.id.textView_expense);
        final TextView incomeTextView = requireActivity().findViewById(R.id.textView_income);
        expenseIncomeSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    animateExpenseIncomeChange(expenseTextView, incomeTextView);
                } else {
                    animateExpenseIncomeChange(incomeTextView, expenseTextView);
                }
            }
        });
        expenseTextView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                expenseIncomeSwitch.setChecked(!expenseIncomeSwitch.isChecked());
            }
        });

        incomeTextView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                expenseIncomeSwitch.setChecked(!expenseIncomeSwitch.isChecked());
            }
        });
    }

    private void animateExpenseIncomeChange(final TextView startBig, TextView startSmall) {
        final float startSize = getResources().getInteger(R.integer.expenseIncomeSwitchLargeText);
        final float endSize = getResources().getInteger(R.integer.expenseIncomeSwitchSmallText);
        // Animation duration in ms
        final int animationDuration = getResources().getInteger(R.integer.expenseIncomeAnimationDuration);

        // enlarge
        textAnimator(startBig, startSize, endSize, animationDuration).start();

        // shrink
        textAnimator(startSmall, endSize, startSize, animationDuration).start();

        int lightTextColor = getColor(requireActivity(), R.color.lightText);
        int darkTextColor = getColor(requireActivity(), R.color.darkText);

        // lighten
        textColorAnimator(startBig, darkTextColor, lightTextColor, animationDuration).start();
        // darken
        textColorAnimator(startSmall, lightTextColor, darkTextColor, animationDuration).start();

    }

    private ValueAnimator textColorAnimator(final TextView textView, int startColor, int endColor, int animationDuration) {
        ValueAnimator colorAnimation = ValueAnimator.ofObject(new ArgbEvaluator(), startColor, endColor);
        colorAnimation.setDuration(animationDuration);
        colorAnimation.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {

            @Override
            public void onAnimationUpdate(ValueAnimator animator) {
                textView.setTextColor((Integer) animator.getAnimatedValue());
            }

        });
        return colorAnimation;
    }

    private ValueAnimator textAnimator(final TextView textView, float startSize, float endSize, int animationDuration) {
        ValueAnimator textSizeAnimation = ValueAnimator.ofFloat(startSize, endSize);
        textSizeAnimation.setDuration(animationDuration);
        textSizeAnimation.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator valueAnimator) {
                float animatedValue = (float) valueAnimator.getAnimatedValue();
                textView.setTextSize(animatedValue);
            }
        });
        return textSizeAnimation;
    }

    public class IconListAdapter extends BaseAdapter {
        private Context mContext;
        private String[] mCategoryIconsArray;

        // Constructor
        public IconListAdapter(Context c) {
            mContext = c;
            mCategoryIconsArray = c.getResources().getStringArray(R.array.iconList);
        }

        @Override
        public int getCount() {
            return mCategoryIconsArray.length;
        }

        @Override
        public Object getItem(int position) {
            return mCategoryIconsArray[position];
        }

        @Override
        public long getItemId(int position) {
            return 0;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            int iconPadding = 20;
            ImageView imageView = new ImageView(mContext);

            IconDrawable icon = new IconDrawable(requireActivity(), mCategoryIconsArray[position]);
            imageView.setImageDrawable(icon);
            imageView.setTag(mCategoryIconsArray[position]);
            imageView.setLayoutParams(new GridView.LayoutParams(130, 130));
            imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            imageView.setPadding(iconPadding, iconPadding, iconPadding, iconPadding);
            imageView.setClickable(false);
            imageView.setFocusable(false);
            imageView.setFocusableInTouchMode(false);
            return imageView;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
