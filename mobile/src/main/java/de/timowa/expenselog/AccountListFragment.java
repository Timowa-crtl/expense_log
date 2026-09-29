package de.timowa.expenselog;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.ContextMenu;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import de.timowa.expenselog.DependencyInjection.ActivityComponent;
import de.timowa.expenselog.databinding.FragmentAccountListBinding;

/**
 * Show list of accounts, with context menu option to eit or delete them
 */
public class AccountListFragment extends BaseFragment implements Button.OnClickListener {

    private FragmentAccountListBinding binding;

    // the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
    private static final String ARG_PARAM1 = "param1";
    private static final String ARG_PARAM2 = "param2";
    private static final int MENU_EDIT = 132;
    private static final int MENU_SETTINGS = 163;
    private static final int MENU_DELETE = 345;
    private static final int CONTEXT_MENU_ID = 221242;

    private List<AccountItem> accountList;
    private MyAdapter mAdapter;

    RecyclerView mRecyclerView;

    private String mParam1;
    private String mParam2;

    @Inject
    Navigator navigator;

    @Inject
    DBAdapter dbAdapter;

    @Inject
    Utility utility;

    /**
     * @param param1 Parameter 1.
     * @param param2 Parameter 2.
     * @return A new instance of fragment HomeFragment.
     */
    public static AccountListFragment newInstance(String param1, String param2) {
        AccountListFragment fragment = new AccountListFragment();
        Bundle args = new Bundle();
        args.putString(ARG_PARAM1, param1);
        args.putString(ARG_PARAM2, param2);
        fragment.setArguments(args);
        return fragment;
    }

    public AccountListFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            mParam1 = getArguments().getString(ARG_PARAM1);
            mParam2 = getArguments().getString(ARG_PARAM2);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        binding = FragmentAccountListBinding.inflate(inflater, container, false);
        View view = binding.getRoot();
        mRecyclerView = binding.recyclerViewAccountList;
        return view;
    }

    @Override
    public void onClick(View v) {

    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        this.initialize();

        accountList = getAccountList();

        // use a linear layout manager
        RecyclerView.LayoutManager mLayoutManager = new LinearLayoutManager(requireActivity().getApplicationContext());
        mRecyclerView.setLayoutManager(mLayoutManager);

        registerForContextMenu(mRecyclerView);

        mRecyclerView.addItemDecoration(new DividerItemDecoration(requireActivity(), DividerItemDecoration.VERTICAL_LIST));
        mRecyclerView.setItemAnimator(new DefaultItemAnimator());

        // specify an adapter
        mAdapter = new MyAdapter(accountList);
        mRecyclerView.setAdapter(mAdapter);

        setupFab();

    }

    private void setupFab() {
        FloatingActionButton fab = this.requireActivity().findViewById(R.id.fab);
        fab.setImageDrawable(ContextCompat.getDrawable(requireActivity(), R.drawable.ic_action_new));
        // hide and show again to address disappearing icon bug in material 1.0.0
        fab.hide();
        fab.show();
        fab.setOnClickListener(view -> {
            // start new account dialog
            showAccountNameDialog(null, 0);
        });

    }

    private void showAccountNameDialog(final AccountItem previousAccount, final int arrayListPosition) {
        final AlertDialog.Builder alertDialog = new AlertDialog.Builder(requireActivity());
        alertDialog.setTitle(getResources().getString(R.string.new_account));
//        alertDialog.setMessage(getResources().getString(R.string.new_account));

        final EditText accountInput = new EditText(requireActivity());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT);
        accountInput.setLayoutParams(lp);

        InputMethodManager imm = (InputMethodManager) requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);

        if (previousAccount != null) {
            accountInput.setText(previousAccount.getTitle());
            alertDialog.setTitle(getResources().getString(R.string.edit_account));
        }

        accountInput.setGravity(Gravity.CENTER);
        accountInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);

        LinearLayout rl = new LinearLayout(requireActivity());
        rl.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        rl.addView(accountInput);
        rl.setPadding(128, 80, 128, 32);
        alertDialog.setView(rl);

        alertDialog.setPositiveButton(getResources().getString(R.string.ok),
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        String accountLabel = accountInput.getText().toString().trim();
                        if (!accountLabel.equals("")) {
                            if (previousAccount == null) {
                                // add new account
                                utility.snackBarMessage(accountLabel + " " + requireActivity().getString(R.string.added));
                                int newAccountId = (int) dbAdapter.newAccount(accountLabel);
                                AccountItem newAccount = new AccountItem();
                                newAccount.setId(newAccountId);
                                newAccount.setTitle(accountLabel);
                                accountList.add(newAccount);
                                mAdapter.notifyItemInserted(accountList.size());
                            } else {
                                // edit account
                                utility.snackBarMessage(accountLabel + " " + requireActivity().getString(R.string.updated));
                                dbAdapter.updateAccount(previousAccount.getId(), accountLabel);
                                accountList.get(arrayListPosition).setTitle(accountLabel);
                                mAdapter.notifyItemChanged(arrayListPosition);
                            }
                        } else {
                            utility.snackBarMessage(requireActivity().getString(R.string.account_name_cannot_be_blank));
                        }
                    }
                });

        alertDialog.setNegativeButton(getResources().getString(R.string.cancel),
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.cancel();
                    }
                });
        alertDialog.show();
    }

    private List<AccountItem> getAccountList() {
        Cursor accountListCursor = dbAdapter.getAccounts();
        AccountItem item;
        ArrayList<AccountItem> tempList = new ArrayList<>();

        if (accountListCursor != null) {
            while (accountListCursor.moveToNext()) {
                item = new AccountItem();
                item.setId(accountListCursor.getInt(DBAdapter.COLUMN_TAG_ID));
                item.setTitle(accountListCursor.getString(DBAdapter.COLUMN_TAG_LABEL));
                tempList.add(item);
            }
        }
        return tempList;
    }

    private void initialize() {
        this.getComponent(ActivityComponent.class).inject(this);
    }

    class MyAdapter extends RecyclerView.Adapter<MyAdapter.ViewHolder> {
        private List<AccountItem> accountItemList;
        private int position;

        public int getPosition() {
            return position;
        }

        public void setPosition(int position) {
            this.position = position;
        }

        class ViewHolder extends RecyclerView.ViewHolder implements View.OnCreateContextMenuListener {
            // each data item is just a string in this case
            TextView mTextView;
            LinearLayout mItemLayout;

            ViewHolder(View v) {
                super(v);
                mTextView = v.findViewById(R.id.textView_accountListItem);
                mItemLayout = v.findViewById(R.id.layout_accountListItem);
                v.setOnCreateContextMenuListener(this);
            }

            @Override
            public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
                menu.add(CONTEXT_MENU_ID, MENU_EDIT, 5, R.string.menu_edit);
                menu.add(CONTEXT_MENU_ID, MENU_SETTINGS, 7, R.string.settings);
                menu.add(CONTEXT_MENU_ID, MENU_DELETE, 10, R.string.menu_delete);
            }
        }

        // Provide a suitable constructor (depends on the kind of dataset)
        MyAdapter(List<AccountItem> accountItemList) {
            this.accountItemList = accountItemList;
        }

        // Create new views (invoked by the layout manager)
        @Override
        public MyAdapter.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            // create a new view
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.account_list_item, parent, false);
            // set the view's size, margins, paddings and layout parameters
            return new ViewHolder(v);
        }

        // Replace the contents of a view (invoked by the layout manager)
        @Override
        public void onBindViewHolder(final ViewHolder holder, final int position) {
            AccountItem accountItem = accountItemList.get(position);
            holder.mTextView.setText(accountItem.getTitle());
            holder.mItemLayout.setOnClickListener(clickListener);
            holder.mItemLayout.setTag(accountItem);

            holder.itemView.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    setPosition(holder.getLayoutPosition());
                    return false;
                }
            });
        }

        View.OnClickListener clickListener = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                AccountItem tag = (AccountItem) view.getTag();
            }
        };

        // Return the size of your dataset (invoked by the layout manager)
        @Override
        public int getItemCount() {
            return (null != accountItemList ? accountItemList.size() : 0);
        }

        @Override
        public void onViewRecycled(ViewHolder holder) {
            holder.itemView.setOnLongClickListener(null);
            super.onViewRecycled(holder);
        }
    }

    // respond to the context menu tap
    @Override
    public boolean onContextItemSelected(@NonNull MenuItem item) {
        if (BuildConfig.DEBUG)
            Log.i("CONTEXT", "account list");
        if (item.getGroupId() == CONTEXT_MENU_ID) {
            int selectedTagPosition;
            try {
                selectedTagPosition = ((MyAdapter) mRecyclerView.getAdapter()).getPosition();
            } catch (Exception e) {
                return super.onContextItemSelected(item);
            }

            switch (item.getItemId()) {
                case MENU_EDIT:
                    AccountItem accountInfo = accountList.get(selectedTagPosition);
                    showAccountNameDialog(accountInfo, selectedTagPosition);
                    break;
                case MENU_SETTINGS:
                    Intent tempIntent = new Intent(requireActivity(), AccountPreferencesActivity.class);
                    tempIntent.putExtra(MainActivity.EXTRAS_KEY_ACCOUNT_ID, accountList.get(selectedTagPosition).getId());
                    requireActivity().startActivity(tempIntent);
                    break;
                case MENU_DELETE:
                    deleteItem(selectedTagPosition);
                    break;
            }
        }
        return super.onContextItemSelected(item);
    }

    private void deleteItem(int itemPosition) {
        // only allow delete if more than one account exists
        if (dbAdapter.getAccounts() != null && dbAdapter.getAccounts().getCount() > 1) {
            final AccountItem accountInfo = accountList.get(itemPosition);

            // warn user they're about to delete
            final int tempItemPosition = itemPosition;
            String warningTitle = requireActivity().getResources().getString(R.string.delete)
                    + " " + accountInfo.getTitle() + "?";

            androidx.appcompat.app.AlertDialog.Builder myAlertDialog = new androidx.appcompat.app.AlertDialog.Builder(requireActivity());
            myAlertDialog.setTitle(warningTitle);
            myAlertDialog.setMessage(requireActivity().getResources().getString(R.string.delete_account_warning));
            myAlertDialog.setPositiveButton(requireActivity().getResources().getString(R.string.menu_delete),
                    new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface arg0, int arg1) {
                            // delete
                            dbAdapter.deleteAccount(accountInfo.getId());
                            accountList.remove(tempItemPosition);
                            mAdapter.notifyItemRemoved(tempItemPosition);
                        }
                    });
            myAlertDialog.setNegativeButton(requireActivity().getResources().getString(R.string.cancel),
                    new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface arg0, int arg1) {
                        }
                    });
            myAlertDialog.show();
        } else {
            animateInvalidEntry();
            utility.snackBarMessage(requireActivity().getString(R.string.last_account_delete_warning));
        }
    }

    private void animateInvalidEntry() {
        FloatingActionButton fab = this.requireActivity().findViewById(R.id.fab);
        Animation animationScaleUp = AnimationUtils.loadAnimation(requireActivity(), R.anim.shake);
        fab.startAnimation(animationScaleUp);
    }

    private class AccountItem {
        private String title;
        private int id;

        public int getId() {
            return id;
        }

        public void setId(int id) {
            this.id = id;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
