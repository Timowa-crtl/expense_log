package de.timowa.expenselog;

import android.content.DialogInterface;
import android.database.Cursor;
import android.os.Bundle;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import de.timowa.expenselog.icons.IconDrawable;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import de.timowa.expenselog.DependencyInjection.ActivityComponent;
import de.timowa.expenselog.databinding.FragmentTagListBinding;

import static androidx.core.content.ContextCompat.getColor;

public class TagListFragment extends BaseFragment implements Button.OnClickListener {

    private FragmentTagListBinding binding;
    // the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
    private static final String ARG_PARAM1 = "param1";
    private static final String ARG_PARAM2 = "param2";
    private static final int MENU_EDIT = 132;
    private static final int MENU_DELETE = 345;
    private static final int CONTEXT_MENU_ID = 7982;

    private List<TagItem> tagList;
    private MyAdapter mAdapter;
    private RecyclerView.LayoutManager mLayoutManager;

    RecyclerView mRecyclerView;

    private String mParam1;
    private String mParam2;

    @Inject
    Navigator navigator;

//    @Inject
//    Activity activity;

    @Inject
    DBAdapter dbAdapter;

    @Inject
    Utility utility;

//    private OnFragmentInteractionListener mListener;

    /**
     * @param param1 Parameter 1.
     * @param param2 Parameter 2.
     * @return A new instance of fragment HomeFragment.
     */
    public static TagListFragment newInstance(String param1, String param2) {
        TagListFragment fragment = new TagListFragment();
        Bundle args = new Bundle();
        args.putString(ARG_PARAM1, param1);
        args.putString(ARG_PARAM2, param2);
        fragment.setArguments(args);
        return fragment;
    }

    public TagListFragment() {
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
        binding = FragmentTagListBinding.inflate(inflater, container, false);
        View view = binding.getRoot();
        mRecyclerView = binding.recyclerViewTagList;

        return view;
    }

    @Override
    public void onClick(View v) {

    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        this.initialize();

        tagList = getTagList();

        // use a linear layout manager
        mLayoutManager = new LinearLayoutManager(requireActivity().getApplicationContext());
        mRecyclerView.setLayoutManager(mLayoutManager);

        registerForContextMenu(mRecyclerView);

        mRecyclerView.addItemDecoration(new DividerItemDecoration(requireActivity(), DividerItemDecoration.VERTICAL_LIST));
        mRecyclerView.setItemAnimator(new DefaultItemAnimator());

        // specify an adapter
        mAdapter = new MyAdapter(tagList);
        mRecyclerView.setAdapter(mAdapter);

        setupFab();

    }

    private void setupFab() {
        FloatingActionButton fab = this.requireActivity().findViewById(R.id.fab);
        fab.setImageDrawable(ContextCompat.getDrawable(requireActivity(), R.drawable.ic_action_new));
        // hide and show again to address disappearing icon bug in material 1.0.0
        fab.hide();
        fab.show();
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                // send to new category frag
                navigator.newTempFragment(NewCategoryFragment.newInstance(NewCategoryFragment.NEW_TAG_KEY, false),
                        getResources().getString(R.string.new_category));
            }
        });

    }

    private List<TagItem> getTagList() {
        Cursor tagListCursor = dbAdapter.getTags();
        TagItem item;
        tagList = new ArrayList<>();

        if (tagListCursor != null) {
            while (tagListCursor.moveToNext()) {
                item = new TagItem();
                item.setId(tagListCursor.getInt(DBAdapter.COLUMN_TAG_ID));
                item.setTitle(tagListCursor.getString(DBAdapter.COLUMN_TAG_LABEL));
                item.setIcon(tagListCursor.getString(DBAdapter.COLUMN_TAG_ICON));
                item.setType(tagListCursor.getInt(DBAdapter.COLUMN_TAG_TYPE));
                tagList.add(item);
            }
        }
        return tagList;
    }

    private void initialize() {
        this.getComponent(ActivityComponent.class).inject(this);
    }

    public class MyAdapter extends RecyclerView.Adapter<MyAdapter.ViewHolder> {
        private List<TagItem> tagItemList;
        private int position;

        public int getPosition() {
            return position;
        }

        public void setPosition(int position) {
            this.position = position;
        }

        public class ViewHolder extends RecyclerView.ViewHolder implements View.OnCreateContextMenuListener {
            // each data item is just a string in this case
            TextView mTextView;
            ImageView mImageView;
            LinearLayout mItemLayout;
            View mIndicatorView;

            ViewHolder(View v) {
                super(v);
                mTextView = v.findViewById(R.id.textView_tagListItem);
                mItemLayout = v.findViewById(R.id.layout_tagListItem);
                mImageView = v.findViewById(R.id.imageView_tagIcon);
                mIndicatorView = v.findViewById(R.id.view_expenseIncomeIndicator);
                v.setOnCreateContextMenuListener(this);
            }

            @Override
            public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
                menu.add(CONTEXT_MENU_ID, MENU_EDIT, 5, R.string.menu_edit);
                menu.add(CONTEXT_MENU_ID, MENU_DELETE, 10, R.string.menu_delete);
            }
        }

        // Provide a suitable constructor (depends on the kind of dataset)
        MyAdapter(List<TagItem> tagItemList) {
            this.tagItemList = tagItemList;
        }

        // Create new views (invoked by the layout manager)
        @NonNull
        @Override
        public MyAdapter.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            // create a new view
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.tag_list_item, parent, false);
            // set the view's size, margins, paddings and layout parameters
            return new ViewHolder(v);
        }

        // Replace the contents of a view (invoked by the layout manager)
        @Override
        public void onBindViewHolder(final ViewHolder holder, final int position) {
            TagItem tagItem = tagItemList.get(position);
            holder.mTextView.setText(tagItem.getTitle());
            try {
                IconDrawable icon = new IconDrawable(requireActivity(), tagItem.getIcon());
                holder.mImageView.setImageDrawable(icon);
                // set background based on expense or income
                holder.mIndicatorView.setVisibility(View.VISIBLE);
                if (tagItem.getType() == 0) {
                    holder.mIndicatorView.setBackgroundColor(getColor(requireActivity(), R.color.expenseColor));
                } else {
                    holder.mIndicatorView.setBackgroundColor(getColor(requireActivity(), R.color.incomeColor));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            holder.mItemLayout.setOnClickListener(clickListener);
            holder.mItemLayout.setTag(tagItem);

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
                TagItem tag = (TagItem) view.getTag();
            }
        };

        // Return the size of your dataset (invoked by the layout manager)
        @Override
        public int getItemCount() {
            return (null != tagItemList ? tagItemList.size() : 0);
        }

        @Override
        public void onViewRecycled(ViewHolder holder) {
            holder.itemView.setOnLongClickListener(null);
            super.onViewRecycled(holder);
        }
    }

    // respond to the context menu tap
    @Override
    public boolean onContextItemSelected(MenuItem item) {
        if (item.getGroupId() == CONTEXT_MENU_ID) {
            int selectedTagPosition;
            try {
                selectedTagPosition = ((MyAdapter) mRecyclerView.getAdapter()).getPosition();
            } catch (Exception e) {
                return super.onContextItemSelected(item);
            }

            switch (item.getItemId()) {
                case MENU_EDIT:
                    TagItem tagInfo = tagList.get(selectedTagPosition);
                    navigator.newTempFragment(NewCategoryFragment.newInstance(tagInfo.getId(), false),
                            getResources().getString(R.string.new_category));
                    break;
                case MENU_DELETE:
                    deleteItem(selectedTagPosition);
                    break;
            }
        }
        return super.onContextItemSelected(item);
    }

    private void deleteItem(int itemPosition) {
        final TagItem tagInfo = tagList.get(itemPosition);

        // warn user they're about to delete
        final int tempItemPosition = itemPosition;
        String warningTitle = requireActivity().getResources().getString(R.string.delete)
                + " " + tagInfo.getTitle() + "?";

        androidx.appcompat.app.AlertDialog.Builder myAlertDialog = new androidx.appcompat.app.AlertDialog.Builder(requireActivity());
        myAlertDialog.setTitle(warningTitle);
        myAlertDialog.setMessage(requireActivity().getResources().getString(R.string.delete_category_warning));
        myAlertDialog.setPositiveButton(requireActivity().getResources().getString(R.string.menu_delete),
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface arg0, int arg1) {
                        // delete
                        dbAdapter.deleteTag(tagInfo.getId());
                        tagList.remove(tempItemPosition);
                        mAdapter.notifyItemRemoved(tempItemPosition);
                    }
                });
        myAlertDialog.setNegativeButton(requireActivity().getResources().getString(R.string.cancel),
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface arg0, int arg1) {
                    }
                });
        myAlertDialog.show();

    }

    public class TagItem {
        private String title;
        private int id;
        private String icon;
        private int type;

        public int getType() {
            return type;
        }

        public void setType(int type) {
            this.type = type;
        }

        public int getId() {
            return id;
        }

        public void setId(int id) {
            this.id = id;
        }

        public String getIcon() {
            return icon;
        }

        public void setIcon(String icon) {
            this.icon = icon;
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
