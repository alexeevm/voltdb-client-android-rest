package voltdb.org.voter;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.Settings;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;


import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.voltdb.restclient.VoltProcedure;
import org.voltdb.restclient.VoltResponse;

public class MainActivity extends AppCompatActivity implements LocationListener {

    private static final String LOCALHOST = "http://10.0.2.2:8080";

    private static final String VALIDATION_SUCCESS = "VALID";
    private static final String VOTE_PROCEDURE = "Vote";

    private static final int MAX_NUM_VOTES = 2;

    private static final int REQUEST_ID_MULTIPLE_PERMISSIONS = 100;

    private LocationManager mLocationManager;
    private String mLocationProvider;

    private TelephonyManager mTelephonyManager;

    private Location mLocation;
    private long mPhoneNumber;
    private int mContestantId;
    private String mVoltDBHost = LOCALHOST;

    TextView mLongitude;
    TextView mLatitude;
    TextView mStatus;
    TextView mPhone;
    EditText mVoltDBURL;
    EditText mContestant;
    EditText mPhoneEdit;

    AtomicBoolean mCallInProgress = new AtomicBoolean(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        Button voltDBButton = (Button) findViewById(R.id.button_id);
        voltDBButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (mCallInProgress.get()) {
                    showToastOnUIThread(getString(R.string.another_call_in_progress), Toast.LENGTH_SHORT);
                    return;
                }
                setStatusOnUIThread(getString(R.string.status_validating_input));
                String validationResult = validateInput();
                if (!VALIDATION_SUCCESS.equals(validationResult))   {
                    showToastOnUIThread(validationResult, Toast.LENGTH_SHORT);
                    return;
                }

                VoltDBVoteTask voteTask = new VoltDBVoteTask();
                // Both tasks runs on the same thread and the select task is guaranteed to run after the connect finishes
                voteTask.execute();
            }
        });

        mLongitude = (TextView) findViewById(R.id.lon_id);
        mLatitude = (TextView) findViewById(R.id.lat_id);
        mStatus = (TextView) findViewById(R.id.status_id);
        mVoltDBURL = (EditText) findViewById(R.id.voltdb_url_id);
        mContestant = (EditText) findViewById(R.id.contestant_id);
        mPhone = (TextView) findViewById(R.id.identified_phone_id);
        mPhoneEdit = (EditText) findViewById(R.id.enter_phone_id);

        if(checkAndRequestPermissions()) {
            // carry on the normal flow, as the case of  permissions  granted.
            identifyPhoneNumber();
            identifyLocation();
        }

        mLocationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if(!mLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            // check if enabled and if not send user to the GSP settings
            // Better solution would be to display a dialog and suggesting to
            // go to the settings
            Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
            startActivity(intent);
        }

        // Define the criteria how to select the locatioin provider -> use default
        Criteria criteria = new Criteria();
        mLocationProvider = mLocationManager.getBestProvider(criteria, false);
        if (mLocationProvider == null) {
            mLocationProvider = LocationManager.GPS_PROVIDER;
        }
        try {
            mLocationManager.requestLocationUpdates(mLocationProvider, 400, 1, this);
            // Update UI just in case
            mLocation = mLocationManager.getLastKnownLocation(mLocationProvider);
            onLocationChanged(mLocation);
        } catch(SecurityException se) {
            Toast toast = Toast.makeText(getApplicationContext(), getString(R.string.no_geo_location_permissions), Toast.LENGTH_SHORT);
            toast.show();
        }
    }

    private  boolean checkAndRequestPermissions() {
        int permissionSendMessage = ContextCompat.checkSelfPermission(this,
                Manifest.permission.SEND_SMS);
        int locationPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION);
        int phonePermission = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE);
        List<String> listPermissionsNeeded = new ArrayList<>();
        if (locationPermission != PackageManager.PERMISSION_GRANTED) {
            listPermissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (locationPermission != PackageManager.PERMISSION_GRANTED) {
            listPermissionsNeeded.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (permissionSendMessage != PackageManager.PERMISSION_GRANTED) {
            listPermissionsNeeded.add(Manifest.permission.READ_SMS);
        }
        if (phonePermission != PackageManager.PERMISSION_GRANTED) {
            listPermissionsNeeded.add(Manifest.permission.READ_PHONE_STATE);
        }
        if (!listPermissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this, listPermissionsNeeded.toArray(new String[listPermissionsNeeded.size()]), REQUEST_ID_MULTIPLE_PERMISSIONS);
            return false;
        }
        return true;
    }

    private void identifyPhoneNumber() {
        mTelephonyManager = (TelephonyManager) getApplicationContext()
                .getSystemService(Context.TELEPHONY_SERVICE);
        String phoneStr = null;
        try {
            phoneStr = mTelephonyManager.getLine1Number();
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (phoneStr != null) {
            TextView phonePrompt = (TextView) findViewById(R.id.phone_prompt_id);
            phonePrompt.setText(getString(R.string.your_phone_number));
            phoneStr = mTelephonyManager.getLine1Number();
            String normilizedPhone = PhoneNumberUtils.formatNumber(phoneStr.substring(1));
            mPhone.setText(normilizedPhone);

            mPhone.setVisibility(View.VISIBLE);
            mPhoneEdit.setVisibility(View.GONE);
        }  else {
            mPhone.setVisibility(View.GONE);
            mPhoneEdit.setVisibility(View.VISIBLE);
        }
    }

    private void identifyLocation() {
        mLocationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if(!mLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            // check if enabled and if not send user to the GSP settings
            // Better solution would be to display a dialog and suggesting to
            // go to the settings
            Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
            startActivity(intent);
        }

        // Define the criteria how to select the locatioin provider -> use default
        Criteria criteria = new Criteria();
        mLocationProvider = mLocationManager.getBestProvider(criteria, false);
        if (mLocationProvider == null) {
            mLocationProvider = LocationManager.GPS_PROVIDER;
        }
        try {
            mLocationManager.requestLocationUpdates(mLocationProvider, 400, 1, this);
            // Update UI just in case
            mLocation = mLocationManager.getLastKnownLocation(mLocationProvider);
            onLocationChanged(mLocation);
        } catch(SecurityException se) {
            showToastOnUIThread(getString(R.string.no_geo_location_permissions), Toast.LENGTH_SHORT);
        }

    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String permissions[], int[] grantResults) {
        // We assume that user has granted all the permissions
        identifyPhoneNumber();
        identifyLocation();
    }

    private String validateInput() {
        // Validate phone number
        String phoneStr = (mPhone.getVisibility() == View.VISIBLE) ?
                mPhone.getText().toString() : mPhoneEdit.getText().toString();
        if (phoneStr == null)  {
            return getString(R.string.empty_phone);
        }
        try {
            mPhoneNumber = Long.parseLong(PhoneNumberUtils.normalizeNumber(phoneStr)) % 10000000000l;
        } catch (NumberFormatException e) {
            return getString(R.string.invalid_phone);
        }

        // Validate Contestant id
        try {
            mContestantId = Integer.parseInt(mContestant.getText().toString());
        } catch (NumberFormatException e) {
            return getString(R.string.invalid_contestant_id);
        }

        // Locatiom
        if (mLocation == null) {
            return getString(R.string.invalid_location);
        }

        return VALIDATION_SUCCESS;
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            if (hasGeoPermissions()) {
                mLocationManager.requestLocationUpdates(mLocationProvider, 400, 1, this);
            }
        } catch (SecurityException se) {
            Toast toast = Toast.makeText(getApplicationContext(), getString(R.string.no_geo_location_permissions), Toast.LENGTH_SHORT);
            toast.show();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            if (hasGeoPermissions()) {
                mLocationManager.removeUpdates(this);
            }
        } catch (SecurityException se) {
            Toast toast = Toast.makeText(getApplicationContext(), getString(R.string.no_geo_location_permissions), Toast.LENGTH_SHORT);
            toast.show();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    // Location Listener Interface
    @Override
    public void onLocationChanged(Location location) {
        mLocation = location;
        if (mLocation != null)  {
            mLongitude.setText(getString(R.string.longitude) + " " + Double.toString(mLocation.getLongitude()));
            mLatitude.setText(getString(R.string.latitude) + " " + Double.toString(mLocation.getLatitude()));
        }
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
    }

    private boolean hasGeoPermissions() {
        return ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private String getBaseURL() {
        if (mVoltDBURL.getText() == null || mVoltDBURL.getText().length() == 0) {
            return LOCALHOST;
        }
        return mVoltDBURL.getText().toString();
    }

    private void showToastOnUIThread(final String toastMsg, final int duration) {
        Runnable show_toast = new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, toastMsg, duration).show();
            }
        };
        runOnUiThread(show_toast);
    }

    private void setStatusOnUIThread(final String status) {
        Runnable setStatus = new Runnable() {
            @Override
            public void run() {
                mStatus.setText(status);
            }
        };
        runOnUiThread(setStatus);
    }


    // VoltDB Stuff


    class VoltDBVoteTask extends AsyncTask<Void, Void, VoltResponse> {

        static final int ERR_VOTER = -1;
        static final int ERR_CALL_INPROGRESS = -2;
        static final int ERR_CONNECTION = -3;
        static final int ERR_INVALID_CONTESTANT = 1;
        static final int ERR_VOTER_OVER_VOTE_LIMIT = 2;
        static final int SUCCESS = 0;

        @Override
        protected void onPreExecute() {
        }

        @Override
        protected VoltResponse doInBackground(Void... params) {
            setStatusOnUIThread(getString(R.string.status_calling));
            if (mCallInProgress.compareAndSet(false, true)) {
                try {
                    return vote(mPhoneNumber, mLocation, mContestantId, MAX_NUM_VOTES);
                } finally {
                    mCallInProgress.set(false);
                }
            } else {
                return new VoltResponse(new Exception(getString(R.string.another_call_in_progress))) ;
            }
        }

        @Override
        protected void onPostExecute(VoltResponse response) {
            String callStatus = null;
            Throwable error = response.getCallError();
            if (error != null) {
                callStatus = error.getMessage();
            }  else {
                List<VoltResponse.VoltTable> results = response.getResults();
                if (results == null || results.isEmpty()) {
                    callStatus = getString(R.string.voltdb_error);
                } else {
                    VoltResponse.VoltTable table = results.get(0);
                    List<Object> data = table.getData();
                    if (data == null || data.isEmpty()) {
                        callStatus = getString(R.string.voltdb_error);
                    } else {
                        try {
                            List<Double> lli =  (List<Double>) data.get(0);
                            int status = lli.get(0).intValue();
                            switch ((int) status) {
                                case ERR_CALL_INPROGRESS: callStatus = getString(R.string.another_call_in_progress); break;
                                case ERR_INVALID_CONTESTANT: callStatus = getString(R.string.invalid_contestant); break;
                                case ERR_VOTER_OVER_VOTE_LIMIT: callStatus = getString(R.string.vote_limit_exceeded); break;
                                case ERR_CONNECTION: callStatus = getString(R.string.failed_to_connect); break;
                                case SUCCESS: callStatus = getString(R.string.success); break;
                                default:
                                    callStatus = getString(R.string.voltdb_error);
                                    break;
                            }
                        } catch (Exception e) {
                            callStatus = getString(R.string.voltdb_error);
                        }
                    }
                }
            }
            setStatusOnUIThread(String.format("%s %s.", getString(R.string.status_voltdb), callStatus));
        }

        @Override
        protected void onCancelled() {
            mCallInProgress.set(false);
            setStatusOnUIThread(getString(R.string.status_call_cancelled));
        }

        @Override
        protected void onCancelled(VoltResponse response) {
            mCallInProgress.set(false);
            setStatusOnUIThread(getString(R.string.status_call_cancelled));
        }

        private VoltResponse vote(long phoneNumber, Location location, int contestantNumber, long maxVotesPerPhoneNumber) {
            // Init Volt Service
            String voltURL = getBaseURL();
            String locationStr = "POINT(" + Double.toString(location.getLongitude()) + " " + Double.toString(location.getLatitude()) + ")";
            // Make a call
            return VoltProcedure.callProcedure(voltURL, VOTE_PROCEDURE, phoneNumber, locationStr, contestantNumber, maxVotesPerPhoneNumber);
        }
    }

}