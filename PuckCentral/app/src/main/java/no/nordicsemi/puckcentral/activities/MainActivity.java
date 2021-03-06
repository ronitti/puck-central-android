package no.nordicsemi.puckcentral.activities;

import android.app.AlertDialog;
import android.app.Dialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.radiusnetworks.ibeacon.IBeacon;

import org.droidparts.activity.Activity;
import org.droidparts.annotation.bus.ReceiveEvents;
import org.droidparts.annotation.inject.InjectDependency;
import org.droidparts.annotation.inject.InjectView;
import org.droidparts.concurrent.task.AsyncTaskResultListener;
import org.droidparts.concurrent.task.SimpleAsyncTask;
import org.droidparts.util.L;

import java.util.ArrayList;
import java.util.UUID;

import no.nordicsemi.puckcentral.R;
import no.nordicsemi.puckcentral.actuators.Actuator;
import no.nordicsemi.puckcentral.adapters.PuckAdapter;
import no.nordicsemi.puckcentral.bluetooth.gatt.CubeConnectionManager;
import no.nordicsemi.puckcentral.bluetooth.gatt.GattManager;
import no.nordicsemi.puckcentral.db.ActionManager;
import no.nordicsemi.puckcentral.db.PuckManager;
import no.nordicsemi.puckcentral.db.RuleManager;
import no.nordicsemi.puckcentral.models.Action;
import no.nordicsemi.puckcentral.models.Puck;
import no.nordicsemi.puckcentral.models.Rule;
import no.nordicsemi.puckcentral.services.GattServices;
import no.nordicsemi.puckcentral.triggers.Trigger;


public class MainActivity extends Activity {

    @InjectView(id = R.id.lvPucks)
    ListView mLvPucks;

    @InjectDependency
    private ActionManager mActionManager;

    @InjectDependency
    private RuleManager mRuleManager;

    @InjectDependency
    private PuckManager mPuckManager;

    @InjectDependency
    private GattManager mGattManager;

    @InjectDependency
    private CubeConnectionManager mCubeConnectionManager;

    private PuckAdapter mPuckAdapter;

    @Override
    public void onPreInject() {
        super.onPreInject();
        setContentView(R.layout.activity_main);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mPuckAdapter = new PuckAdapter(this, (new PuckManager(this)).select());
        mLvPucks.addHeaderView(new View(this));
        mLvPucks.addFooterView(new View(this));
        mLvPucks.setAdapter(mPuckAdapter);
    }

    @ReceiveEvents(name = Trigger.TRIGGER_ADD_ACTUATOR_FOR_EXISTING_RULE)
    public void addActuatorForExistingRule(String _, Object rule) {
        selectActuatorDialog((Rule) rule);
    }

    @ReceiveEvents(name = Trigger.TRIGGER_REMOVE_RULE)
    public void removeRule(String _, final Rule rule) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(R.string.rule_remove)
                .setPositiveButton(getString(R.string.delete), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        mRuleManager.delete(rule.id);
                        mPuckAdapter.requeryData();
                    }
                })
                .setNegativeButton(getString(R.string.abort), null);
        builder.create().show();
    }

    @ReceiveEvents(name = Trigger.TRIGGER_CLOSEST_PUCK_CHANGED)
    public void closestPuckChanged(String _, Puck closestPuck) {
        mPuckAdapter.closestPuckChanged(closestPuck);
    }

    @ReceiveEvents(name = Trigger.TRIGGER_CONNECTION_STATE_CHANGED)
    public void connectionStateChanged(String _, GattManager.ConnectionStateChangedBundle connectionStateChangedBundle) {
        mPuckAdapter.connectionStateChanged(connectionStateChangedBundle);
        mCubeConnectionManager.connectionStateChanged(connectionStateChangedBundle);
    }

    boolean currentlyAddingZone = false;
    @ReceiveEvents(name = Trigger.TRIGGER_ZONE_DISCOVERED)
    public void createDiscoveredZoneModal(String _, final IBeacon iBeacon) {
        if (currentlyAddingZone) {
            return;
        } else if (mPuckManager.forIBeacon(iBeacon) != null) {
            Toast.makeText(this,
                    getString(R.string.location_puck_already_paired),
                    Toast.LENGTH_SHORT).show();
            return;
        }

        currentlyAddingZone = true;

        ArrayList<UUID> defaultServiceUUIDs = new ArrayList<>();
        defaultServiceUUIDs.add(GattServices.LOCATION_SERVICE_UUID);
        final Puck newPuck = new Puck(null,
                iBeacon.getMinor(),
                iBeacon.getMajor(),
                iBeacon.getProximityUuid(),
                iBeacon.getBluetoothAddress(),
                defaultServiceUUIDs);

        final View view = getLayoutInflater().inflate(R.layout.dialog_location_puck_add, null, false);
        ((TextView) view.findViewById(R.id.tvLocationPuckIdentifier)).setText(newPuck.getFormattedUUID());

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setView(view)
                .setTitle(getString(R.string.puck_discovered_dialog_title))
                .setPositiveButton(getString(R.string.accept), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String locationPuckName = ((TextView) view.findViewById(R.id
                                .etLocationPuckName)).getText().toString();
                        newPuck.setName(locationPuckName);
                        mPuckAdapter.create(newPuck);

                        new FetchPuckServices(MainActivity.this, null, newPuck).execute();
                    }
                })
                .setNegativeButton(getString(R.string.reject), null)
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialog) {
                        currentlyAddingZone = false;
                    }
                });

        AlertDialog dialog = builder.create();
        dialog.show();
    }

    /**
     * Fetches gatt service UUIDs for a given puck,
     * and adds themthe pucks list of serviceUUIDs.
     *
     * If a puck advertises as non-connectable, the onServicesDiscovered callback
     * will (propably) never be triggered, not updating the puck.
     *
     * This approach was chosen as android provides no way to check if a BLE device
     * is connectable or not (that i could find).
     */
    private class FetchPuckServices extends SimpleAsyncTask<Void> {
        private Puck mPuck;

        public FetchPuckServices(Context ctx, AsyncTaskResultListener<Void> resultListener, Puck puck) {
            super(ctx, resultListener);
            mPuck = puck;
        }

        @Override
        protected Void onExecute() throws Exception {
            BluetoothAdapter bluetoothAdapter = ((BluetoothManager) getSystemService(Context
                    .BLUETOOTH_SERVICE)).getAdapter();
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(mPuck.getAddress());
            L.e("Starting service discovery");
            device.connectGatt(MainActivity.this, true, new BluetoothGattCallback() {
                @Override
                public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                    super.onConnectionStateChange(gatt, status, newState);

                    L.e("Got status " + status + " and state " + newState);
                    // Catch-all for a variety of undocumented error codes.
                    // Documented at https://code.google.com/r/naranjomanuel-opensource-broadcom-ble/source/browse/api/java/src/com/broadcom/bt/le/api/BleConstants.java?r=f535f31ec89eb3076a2b75ddf586f4b3fc44384b
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        L.e("Ouch! Disconnecting! status: " + status + " newState " + newState);
                        gatt.disconnect();
                        return;
                    }

                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        L.e("Connected to service!");
                        gatt.discoverServices();
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        L.e("Link disconnected");
                        gatt.close();
                    } else {
                        L.e("Received something else, ");
                    }
                }

                @Override
                public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                    super.onServicesDiscovered(gatt, status);
                    ArrayList<UUID> serviceUUIDs = mPuck.getServiceUUIDs();

                    for (BluetoothGattService service : gatt.getServices()) {
                        serviceUUIDs.add(service.getUuid());
                    }
                    mPuck.setServiceUUIDs(serviceUUIDs);
                    mPuckManager.update(mPuck);
                    L.e("Now has services: " + mPuck.getServiceUUIDs());
                    gatt.disconnect();
                }
            });
            return null;
        }
    }

    @ReceiveEvents(name = Trigger.TRIGGER_ADD_RULE_FOR_EXISTING_PUCK)
    public void addRuleForExistingPuck(String _, Object puck) {
        Rule rule = new Rule();
        rule.setPuck((Puck) puck);
        selectTriggerDialog(rule);
    }

    public void selectTriggerDialog(final Rule rule) {
        ArrayList<UUID> serviceUUIDs = rule.getPuck().getServiceUUIDs();
        final String[] triggers = GattServices.getTriggersForServiceUUIDs(serviceUUIDs);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.select_trigger))
                .setItems(triggers, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        rule.setTrigger(triggers[which]);
                        selectActuatorDialog(rule);
                    }
                })
                .setNegativeButton(getString(R.string.abort), null);

        builder.create().show();
    }

    public void selectActuatorDialog(final Rule rule) {
        final ArrayList<Actuator> actuators = Actuator.getActuators();
        String[] actuatorDescriptions = new String[actuators.size()];
        for (int i=0; i< actuators.size(); i++) {
            actuatorDescriptions[i] = actuators.get(i).describeActuator();
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.select_actuator))
                .setItems(actuatorDescriptions,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                Action action = new Action(
                                        actuators.get(which).getId(),
                                        null);

                                Dialog actuatorDialog = actuators.get(which)
                                        .getActuatorDialog(MainActivity.this, action, rule, new Actuator.ActuatorDialogFinishListener() {
                                            @Override
                                            public void onActuatorDialogFinish(Action action, Rule rule) {
                                                mActionManager.create(action);
                                                mRuleManager.createOrExtendExisting(rule);
                                                // This looks at the primary key of the rule entry. Therefore, if we start
                                                // the process with a new Rule object, even if the trigger and puck match,
                                                // they won't extend the rule we actually want to extend.
                                                mPuckAdapter.requeryData();
                                            }
                                        });

                                actuatorDialog.show();
                            }
                        }
                )
                .setNegativeButton(getString(R.string.abort), null);

        builder.create().show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();
        switch (id) {
            case R.id.action_trigger:
                final ArrayList<Puck> pucks = mPuckManager.getAll();
                if(pucks.size() > 0) {
                    new Thread() {
                        @Override
                        public void run() {
                            Trigger.trigger(pucks.get(0), Trigger.TRIGGER_ENTER_ZONE);
                        }
                    }.start();
                }
                return true;

            default:
                return super.onOptionsItemSelected(item);
        }
    }
}
