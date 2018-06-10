/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.android.sync.activities;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.support.v7.widget.AppCompatSpinner;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.xwiki.android.sync.Constants;
import org.xwiki.android.sync.R;
import org.xwiki.android.sync.auth.AuthenticatorActivity;
import org.xwiki.android.sync.bean.SearchResult;
import org.xwiki.android.sync.bean.SearchResultContainer;
import org.xwiki.android.sync.bean.SerachResults.CustomSearchResultContainer;
import org.xwiki.android.sync.bean.XWikiGroup;
import org.xwiki.android.sync.utils.SharedPrefsUtils;
import org.xwiki.android.sync.utils.SystemTools;

import java.util.ArrayList;
import java.util.List;

import rx.android.schedulers.AndroidSchedulers;
import rx.functions.Action1;
import rx.schedulers.Schedulers;

import static org.xwiki.android.sync.AppContext.getApiManager;

/**
 * SettingSyncViewFlipper
 */
public class SettingSyncViewFlipper extends BaseViewFlipper {
    private static final String TAG = "SettingSyncViewFlipper";

    ListView mListView = null;
    GroupListAdapter mGroupAdapter;
    UserListAdapter mUsersAdapter;
    private List<XWikiGroup> groupList;
    private List<SearchResult> searchResults;
    private int SYNC_TYPE = Constants.SYNC_TYPE_NO_NEED_SYNC;

    private volatile Boolean groupsAreLoading = false;
    private volatile Boolean allAreLoading = false;

    public SettingSyncViewFlipper(AuthenticatorActivity activity, View contentRootView) {
        super(activity, contentRootView);
        initView();
    }

    @Override
    public void doNext() {
        syncSettingComplete();
        mActivity.finish();
        //mActivity.checkPermissions();
    }

    @Override
    public void doPrevious() {
        mActivity.finish();
    }

    private void initView(){
        Button versionCheckButton = (Button) findViewById(R.id.version_check);
        versionCheckButton.setText(
            String.format(
                mContext.getString(R.string.versionTemplate),
                SystemTools.getAppVersionName(mContext)
            )
        );
        versionCheckButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAppMarket(mContext);
            }
        });

        findViewById(R.id.settingsSyncRefreshCurrentTypeListButton).setOnClickListener(
            new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    initData();
                }
            }
        );

        mListView = (ListView) findViewById(R.id.list_view);
        mListView.setEmptyView(
            findViewById(R.id.syncTypeGetErrorContainer)
        );
        groupList = new ArrayList<>();
        searchResults = new ArrayList<>();
        mGroupAdapter = new GroupListAdapter(mContext, groupList);
        mUsersAdapter = new UserListAdapter(mContext, searchResults);
        initData();
        mListView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);

        AppCompatSpinner selectSyncSpinner = getSelectSyncSpinner();
        selectSyncSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if(position == Constants.SYNC_TYPE_NO_NEED_SYNC){
                    mListView.setVisibility(View.GONE);
                }else {
                    if(position == Constants.SYNC_TYPE_SELECTED_GROUPS){
                        mListView.setVisibility(View.VISIBLE);
                        mListView.setAdapter(mGroupAdapter);
                        mGroupAdapter.refresh(groupList);
                        refreshProgressBar();
                    }else{
                        mListView.setVisibility(View.VISIBLE);
                        mListView.setAdapter(mUsersAdapter);
                        mUsersAdapter.refresh(searchResults);
                        refreshProgressBar();
                    }
                }
                SYNC_TYPE = position;
                ((TextView) view).setTextColor(Color.BLACK);
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });


        SYNC_TYPE = SharedPrefsUtils.getValue(mContext, Constants.SYNC_TYPE, Constants.SYNC_TYPE_ALL_USERS);
        selectSyncSpinner.setSelection(SYNC_TYPE);
    }

    private AppCompatSpinner getSelectSyncSpinner() {
        return (AppCompatSpinner) findViewById(R.id.select_spinner);
    }

    private ProgressBar getProgressBar() {
        return (ProgressBar) findViewById(R.id.list_viewProgressBar);
    }

    private View getListViewContainer() {
        return findViewById(R.id.settingsSyncListViewContainer);
    }

    private void refreshProgressBar() {
        Integer selectedSyncType = getSelectSyncSpinner().getSelectedItemPosition();
        final Boolean progressBarVisible;
        switch (selectedSyncType) {
            case Constants.SYNC_TYPE_SELECTED_GROUPS:
                progressBarVisible = groupsAreLoading;
            break;
            case Constants.SYNC_TYPE_ALL_USERS:
                progressBarVisible = allAreLoading;
            break;
            default:
                progressBarVisible = false;
            break;
        }
        mActivity.runOnUiThread(
            new Runnable() {
                @Override
                public void run() {
                    if (progressBarVisible) {
                        getProgressBar().setVisibility(View.VISIBLE);
                        getListViewContainer().setVisibility(View.GONE);
                    } else {
                        getProgressBar().setVisibility(View.GONE);
                        getListViewContainer().setVisibility(View.VISIBLE);
                    }
                }
            }
        );
    }

    private void initData() {
        if (groupList.isEmpty()) {
            groupsAreLoading = true;
            getApiManager().getXwikiServicesApi().availableGroups(
                Constants.LIMIT_MAX_SYNC_USERS
            )
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    new Action1<CustomSearchResultContainer<XWikiGroup>>() {
                        @Override
                        public void call(CustomSearchResultContainer<XWikiGroup> xWikiGroupCustomSearchResultContainer) {
                            List<XWikiGroup> searchResults = xWikiGroupCustomSearchResultContainer.searchResults;
                            if (searchResults != null) {
                                groupList.clear();
                                groupList.addAll(searchResults);
                                if (getSelectSyncSpinner().getSelectedItemPosition() == Constants.SYNC_TYPE_SELECTED_GROUPS) {
                                    mListView.setAdapter(mGroupAdapter);
                                    mGroupAdapter.refresh(groupList);
                                }
                            }
                            groupsAreLoading = false;
                            refreshProgressBar();
                        }
                    },
                    new Action1<Throwable>() {
                        @Override
                        public void call(Throwable throwable) {
                            groupsAreLoading = false;
                            mActivity.runOnUiThread(
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        Toast.makeText(
                                            mActivity,
                                            R.string.cantGetGroups,
                                            Toast.LENGTH_SHORT
                                        ).show();
                                    }
                                }
                            );
                            refreshProgressBar();
                        }
                    }
                );
        }
        if (searchResults.isEmpty()) {
            allAreLoading = true;
            getApiManager().getXwikiServicesApi().getAllUsersPreview()
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    new Action1<SearchResultContainer>() {
                        @Override
                        public void call(SearchResultContainer searchResultContainer) {
                            searchResults.clear();
                            searchResults.addAll(searchResultContainer.searchResults);
                            if (getSelectSyncSpinner().getSelectedItemPosition() == Constants.SYNC_TYPE_ALL_USERS) {
                                mListView.setAdapter(mUsersAdapter);
                                mUsersAdapter.refresh(searchResults);
                            }
                            allAreLoading = false;
                            refreshProgressBar();
                        }
                    },
                    new Action1<Throwable>() {
                        @Override
                        public void call(Throwable throwable) {
                            mActivity.runOnUiThread(
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        Toast.makeText(
                                            mActivity,
                                            R.string.cantGetAllUsers,
                                            Toast.LENGTH_SHORT
                                        ).show();
                                    }
                                }
                            );
                            allAreLoading = false;
                            refreshProgressBar();
                        }
                    }
                );
        }
        refreshProgressBar();
    }

    public void noPermissions(){
        Toast.makeText(mContext, "Please grant the permission in the app settings", Toast.LENGTH_SHORT).show();
    }


    public void syncSettingComplete() {
        //check changes. if no change, directly return
        int oldSyncType = SharedPrefsUtils.getValue(mContext, Constants.SYNC_TYPE, -1);
        if(oldSyncType == SYNC_TYPE && SYNC_TYPE != Constants.SYNC_TYPE_SELECTED_GROUPS){
            return;
        }
        //if has changes, set sync
        if(SYNC_TYPE == Constants.SYNC_TYPE_NO_NEED_SYNC){
            SharedPrefsUtils.putValue(mContext.getApplicationContext(), Constants.SYNC_TYPE, Constants.SYNC_TYPE_NO_NEED_SYNC);
            resetSync(false);
        } else if (SYNC_TYPE == Constants.SYNC_TYPE_ALL_USERS) {
            SharedPrefsUtils.putValue(mContext.getApplicationContext(), Constants.SYNC_TYPE, Constants.SYNC_TYPE_ALL_USERS);
            resetSync(true);
        } else if(SYNC_TYPE == Constants.SYNC_TYPE_SELECTED_GROUPS){
            //compare to see if there are some changes.
            if(oldSyncType == SYNC_TYPE && compareSelectGroups()){
                return;
            }
            List<XWikiGroup> list = mGroupAdapter.getSelectGroups();
            if (list != null && list.size() > 0) {
                List<String> groupIdList = new ArrayList<>();
                for (XWikiGroup iGroup : list) {
                    groupIdList.add(iGroup.id);
                }
                SharedPrefsUtils.putArrayList(mContext.getApplicationContext(), Constants.SELECTED_GROUPS, groupIdList);
            } else {
                SharedPrefsUtils.putArrayList(mContext.getApplicationContext(), Constants.SELECTED_GROUPS, new ArrayList<String>());
            }
            SharedPrefsUtils.putValue(mContext.getApplicationContext(), Constants.SYNC_TYPE, Constants.SYNC_TYPE_SELECTED_GROUPS);
            resetSync(true);
        }
        mActivity.finish();
    }

    private void resetSync(boolean flag) {
        AccountManager mAccountManager = AccountManager.get(mContext.getApplicationContext());
        Account availableAccounts[] = mAccountManager.getAccountsByType(Constants.ACCOUNT_TYPE);
        Account account = availableAccounts[0];
        if (flag) {
            //reset sync
            mAccountManager.setUserData(account, Constants.SYNC_MARKER_KEY, null);
            ContentResolver.cancelSync(account, ContactsContract.AUTHORITY);
            ContentResolver.setIsSyncable(account, ContactsContract.AUTHORITY, 1);
            ContentResolver.setSyncAutomatically(account, ContactsContract.AUTHORITY, true);
            //Turn on periodic syncing
            ContentResolver.addPeriodicSync(
                    account,
                    ContactsContract.AUTHORITY,
                    Bundle.EMPTY,
                    Constants.SYNC_INTERVAL);
            ContentResolver.requestSync(account, ContactsContract.AUTHORITY, Bundle.EMPTY);
        } else {
            //don't sync
            ContentResolver.cancelSync(account, ContactsContract.AUTHORITY);
            ContentResolver.setIsSyncable(account, ContactsContract.AUTHORITY, 0);
        }
    }

    private boolean compareSelectGroups(){
        //new
        List<XWikiGroup> newList = mGroupAdapter.getSelectGroups();
        //old
        List<String> oldList = SharedPrefsUtils.getArrayList(mContext.getApplicationContext(), Constants.SELECTED_GROUPS);
        if(newList == null && oldList == null){
            return true;
        }else if(newList != null && oldList != null){
            if(newList.size() != oldList.size()){
                return false;
            }else{
                for(XWikiGroup item : newList){
                    if(!oldList.contains(item.id)){
                        return false;
                    }
                }
                return true;
            }
        }else{
            return false;
        }
    }





    private  void openAppMarket(Context context) {
        Intent rateIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + context.getPackageName()));
        boolean marketFound = false;
        // find all applications able to handle our rateIntent
        final List<ResolveInfo> otherApps = context.getPackageManager().queryIntentActivities(rateIntent, 0);
        for (ResolveInfo otherApp: otherApps) {
            // look for Google Play application
            if (otherApp.activityInfo.applicationInfo.packageName.equals("com.android.vending")) {
                ActivityInfo otherAppActivity = otherApp.activityInfo;
                ComponentName componentName = new ComponentName(
                        otherAppActivity.applicationInfo.packageName,
                        otherAppActivity.name
                );
                rateIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                rateIntent.setComponent(componentName);
                context.startActivity(rateIntent);
                marketFound = true;
                break;
            }
        }
        // if GooglePlay not present on device, open web browser
        if (!marketFound) {
            Intent webIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id="+context.getPackageName()));
            context.startActivity(webIntent);
        }
    }

}