/*
 * Copyright 2018 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.wallet.activities;

import android.content.Intent;
import android.content.SharedPreferences;
import android.databinding.DataBindingUtil;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.ActionBar;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import akka.actor.ActorRef;
import fr.acinq.bitcoin.MilliSatoshi;
import fr.acinq.bitcoin.Satoshi;
import fr.acinq.eclair.CoinUnit;
import fr.acinq.eclair.CoinUtils;
import fr.acinq.eclair.Features;
import fr.acinq.eclair.channel.CLOSING$;
import fr.acinq.eclair.channel.NEGOTIATING$;
import fr.acinq.eclair.channel.OFFLINE$;
import fr.acinq.eclair.channel.SHUTDOWN$;
import fr.acinq.eclair.channel.SYNCING$;
import fr.acinq.eclair.channel.WAIT_FOR_ACCEPT_CHANNEL$;
import fr.acinq.eclair.channel.WAIT_FOR_FUNDING_CONFIRMED$;
import fr.acinq.eclair.channel.WAIT_FOR_FUNDING_CREATED$;
import fr.acinq.eclair.channel.WAIT_FOR_FUNDING_INTERNAL$;
import fr.acinq.eclair.channel.WAIT_FOR_FUNDING_LOCKED$;
import fr.acinq.eclair.channel.WAIT_FOR_FUNDING_SIGNED$;
import fr.acinq.eclair.channel.WAIT_FOR_INIT_INTERNAL$;
import fr.acinq.eclair.channel.WAIT_FOR_OPEN_CHANNEL$;
import fr.acinq.eclair.router.NORMAL$;
import fr.acinq.eclair.wallet.EclairEventService;
import fr.acinq.eclair.wallet.R;
import fr.acinq.eclair.wallet.adapters.LocalChannelItemHolder;
import fr.acinq.eclair.wallet.customviews.DataRow;
import fr.acinq.eclair.wallet.databinding.ActivityChannelDetailsBinding;
import fr.acinq.eclair.wallet.fragments.CloseChannelDialog;
import fr.acinq.eclair.wallet.utils.WalletUtils;

public class ChannelDetailsActivity extends EclairActivity {

  private static final String TAG = "ChannelDetailsActivity";

  public static final Set<String> STATE_MUTUAL_CLOSE = new HashSet<>(Arrays.asList(WAIT_FOR_INIT_INTERNAL$.MODULE$.toString(), WAIT_FOR_OPEN_CHANNEL$.MODULE$.toString(), WAIT_FOR_ACCEPT_CHANNEL$.MODULE$.toString(), WAIT_FOR_FUNDING_INTERNAL$.MODULE$.toString(), WAIT_FOR_FUNDING_CREATED$.MODULE$.toString(), WAIT_FOR_FUNDING_SIGNED$.MODULE$.toString(), NORMAL$.MODULE$.toString()));
  public static final Set<String> STATE_FORCE_CLOSE = new HashSet<>(Arrays.asList(WAIT_FOR_FUNDING_CONFIRMED$.MODULE$.toString(), WAIT_FOR_FUNDING_LOCKED$.MODULE$.toString(), NORMAL$.MODULE$.toString(), SHUTDOWN$.MODULE$.toString(), NEGOTIATING$.MODULE$.toString(), OFFLINE$.MODULE$.toString(), SYNCING$.MODULE$.toString()));

  private CloseChannelDialog mCloseChannelDialog;
  private ActivityChannelDetailsBinding mBinding;
  private String mChannelId;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_channel_details);
    mBinding = DataBindingUtil.setContentView(this, R.layout.activity_channel_details);

    Toolbar toolbar = findViewById(R.id.toolbar);
    setSupportActionBar(toolbar);
    ActionBar ab = getSupportActionBar();
    ab.setDisplayHomeAsUpEnabled(true);
    Intent intent = getIntent();
    mChannelId = intent.getStringExtra(LocalChannelItemHolder.EXTRA_CHANNEL_ID);
  }

  @Override
  protected void onPause() {
    // dismiss the pin dialog if it exists to prevent leak.
    if (mCloseChannelDialog != null) {
      mCloseChannelDialog.dismiss();
    }
    super.onPause();
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (checkInit(ChannelDetailsActivity.class.getSimpleName(), mChannelId)) {
      refreshChannel();
    }
  }

  @Override
  protected void onNewIntent(final Intent intent) {
    super.onNewIntent(intent);
    mChannelId = intent.getStringExtra(LocalChannelItemHolder.EXTRA_CHANNEL_ID);
  }

  private void refreshChannel() {
    try {
      final Map.Entry<ActorRef, EclairEventService.ChannelDetails> channel = getChannel(mChannelId);
      if (channel == null) {
        Log.d(TAG, "could not find channel with id=" + mChannelId);
        Toast.makeText(this, getString(R.string.channeldetails_unknown), Toast.LENGTH_LONG).show();
      } else if (channel.getValue() != null && channel.getKey() != null && channel.getValue() != null) {

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        final CoinUnit prefUnit = WalletUtils.getPreferredCoinUnit(prefs);
        final String state = channel.getValue().state;

        mBinding.balance.setAmountMsat(channel.getValue().balanceMsat);
        mBinding.state.setText(state);

        if (NORMAL$.MODULE$.toString().equals(state)) {
          mBinding.state.setTextColor(ContextCompat.getColor(getApplicationContext(), R.color.green));
        } else if (OFFLINE$.MODULE$.toString().equals(state) || state.startsWith("ERR_")) {
          mBinding.state.setTextColor(ContextCompat.getColor(getApplicationContext(), R.color.red_faded));
        } else {
          mBinding.state.setTextColor(ContextCompat.getColor(getApplicationContext(), R.color.orange));
        }

        if (CLOSING$.MODULE$.toString().equals(state)) {
          mBinding.closingTypeView.setVisibility(View.VISIBLE);
          if (channel.getValue().isCooperativeClosing) {
            mBinding.closingType.setText(getString(R.string.channeldetails_closingtype_mutual));
          } else if (channel.getValue().isLocalClosing) {
            mBinding.closingType.setText(getString(R.string.channeldetails_closingtype_local));
          } else if (channel.getValue().isRemoteClosing) {
            mBinding.closingType.setText(getString(R.string.channeldetails_closingtype_remote));
          } else {
            mBinding.closingType.setText(getString(R.string.channeldetails_closingtype_other));
          }
        }

        mCloseChannelDialog = new CloseChannelDialog(ChannelDetailsActivity.this, dialog -> finish(), R.style.CustomAlertDialog, channel.getKey(),
          STATE_MUTUAL_CLOSE.contains(state), STATE_FORCE_CLOSE.contains(state));
        mBinding.closeButton.setOnClickListener(v -> mCloseChannelDialog.show());
        mBinding.closeButton.setVisibility(STATE_MUTUAL_CLOSE.contains(state) || STATE_FORCE_CLOSE.contains(state) ? View.VISIBLE : View.GONE);

        mBinding.nodeid.setValue(channel.getValue().remoteNodeId);
        mBinding.capacity.setValue(CoinUtils.formatAmountInUnit(channel.getValue().capacityMsat, prefUnit, true));
        mBinding.channelId.setValue(channel.getValue().channelId);
        mBinding.channelId.actionButton.setOnClickListener(v -> openRawDataWindow());
        mBinding.shortChannelId.setValue(channel.getValue().shortChannelId);
        displayFeatureSupport(mBinding.advancedRoutingSync, Features.hasFeature(channel.getValue().localFeatures, Features.INITIAL_ROUTING_SYNC_BIT_OPTIONAL()));
        displayFeatureSupport(mBinding.dataLossProtection, Features.hasFeature(channel.getValue().localFeatures, Features.OPTION_DATA_LOSS_PROTECT_OPTIONAL())
          || Features.hasFeature(channel.getValue().localFeatures, Features.OPTION_DATA_LOSS_PROTECT_MANDATORY()));
        mBinding.toSelfDelay.setValue(String.valueOf(channel.getValue().toSelfDelayBlocks));
        mBinding.reserve.setValue(CoinUtils.formatAmountInUnit(new Satoshi(channel.getValue().channelReserveSat), prefUnit, true));
        mBinding.countHtlcsInflight.setValue(String.valueOf(channel.getValue().htlcsInFlightCount));
        mBinding.minimumHtlcAmount.setValue(CoinUtils.formatAmountInUnit(new MilliSatoshi(channel.getValue().minimumHtlcAmountMsat), prefUnit, true));
        mBinding.transactionId.setValue(channel.getValue().fundingTxId);
        mBinding.transactionId.actionButton.setOnClickListener(WalletUtils.getOpenTxListener(channel.getValue().fundingTxId));
      }
    } catch (Exception e) {
      Log.w(TAG, "could not read channel details with cause=" + e.getMessage());
    }
  }

  private void displayFeatureSupport(final DataRow view, final boolean isSupported) {
    if (isSupported) {
      view.setValue(getString(R.string.channeldetails_feature_supported));
      view.getValueView().setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_circle_check, 0, 0, 0);
    } else {
      view.setValue(getString(R.string.channeldetails_feature_not_supported));
      view.getValueView().setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_circle_cross, 0, 0, 0);
    }
    view.getValueView().setCompoundDrawablePadding(12);
  }

  private void openRawDataWindow() {
    Intent intent = new Intent(getApplicationContext(), ChannelRawDataActivity.class);
    intent.putExtra(LocalChannelItemHolder.EXTRA_CHANNEL_ID, this.mChannelId);
    startActivity(intent);
  }

  private Map.Entry<ActorRef, EclairEventService.ChannelDetails> getChannel(String channelId) {
    for (Map.Entry<ActorRef, EclairEventService.ChannelDetails> e : EclairEventService.getChannelsMap().entrySet()) {
      if (e.getValue().channelId.equals(channelId)) {
        return e;
      }
    }
    return null;
  }
}
