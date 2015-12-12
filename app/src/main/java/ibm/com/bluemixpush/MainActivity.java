package ibm.com.bluemixpush;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.Properties;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.net.URI;

//MobileFirst core library and Push Notification library
import com.ibm.mobilefirstplatform.clientsdk.android.core.api.BMSClient;
import com.ibm.mobilefirstplatform.clientsdk.android.push.api.MFPPush;
import com.ibm.mobilefirstplatform.clientsdk.android.push.api.MFPPushException;
import com.ibm.mobilefirstplatform.clientsdk.android.push.api.MFPPushNotificationListener;
import com.ibm.mobilefirstplatform.clientsdk.android.push.api.MFPPushResponseListener;
import com.ibm.mobilefirstplatform.clientsdk.android.push.api.MFPSimplePushNotification;

//Cloudant Notification library
import com.cloudant.sync.datastore.Datastore;
import com.cloudant.sync.datastore.DatastoreManager;
import com.cloudant.sync.datastore.MutableDocumentRevision;
import com.cloudant.sync.datastore.DocumentRevision;
import com.cloudant.sync.datastore.DocumentBodyFactory;
import com.cloudant.sync.replication.ReplicatorBuilder;
import com.cloudant.sync.replication.Replicator;
import com.cloudant.sync.notifications.ReplicationCompleted;
import com.cloudant.sync.notifications.ReplicationErrored;

import com.google.common.eventbus.Subscribe;

import org.json.JSONException;
import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {

    private static final String APP_ID = "applicationID";
    private static final String APP_SECRET = "applicationSecret";
    private static final String APP_ROUTE = "applicationRoute";
    private static final String PROPS_FILE = "bluemixpush.properties";
    private static final String CLASS_NAME = MainActivity.class.getSimpleName();

    private TextView textView;
    private TextView logView;

    private MFPPush push = null;
    private MFPPushNotificationListener notificationListener = null;



    private String cloudantAPIKey;
    private String cloudantAPIPassword;
    private String cloudtantAPIUsername;
    private URI cloudantURI;
    private File path;
    private Datastore datastore = null;
    private DatastoreManager manager = null;
    private String dbname = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        textView = (TextView)findViewById(R.id.textView);
        logView  = (TextView)findViewById(R.id.logView);

        // Read Bluemix backend info from properties file
        Properties props = new java.util.Properties();

        final Context context = getApplicationContext();
        try {
            AssetManager assetManager = context.getAssets();
            props.load(assetManager.open(PROPS_FILE));
            Log.i(CLASS_NAME, "Found configuration file: " + PROPS_FILE);
        } catch (FileNotFoundException e) {
            Log.e(CLASS_NAME, "The properties file was not found.", e);
        } catch (IOException e) {
            Log.e(CLASS_NAME, "The properties file could not be read properly.", e);
        }

        // Initialize the core Bluemix Services
        try {
            BMSClient.getInstance().initialize( getApplicationContext(), props.getProperty(APP_ROUTE), props.getProperty(APP_ID));

        } catch (MalformedURLException e) {
            e.printStackTrace();
        }

        // Initialize the Push Notification Services
        push = MFPPush.getInstance();
        push.initialize(getApplicationContext());

        //Register the device to push services
        push.register(new MFPPushResponseListener<String>() {
            @Override
            public void onSuccess(String response) {
                updateTextView(textView, "Push service connection succeed \n");
            }

            @Override
            public void onFailure(MFPPushException exception) {
                updateTextView(textView, "Push service connection failed \n" );

            }
        });

        //Register the push notification listener to push serivce
        notificationListener = new MFPPushNotificationListener() {

            @Override
            public void onReceive(MFPSimplePushNotification mfpSimplePushNotification) {
                showSimplePushMessage(mfpSimplePushNotification);
            }
        };


        // Create Cloudant offline datastore
        path = getApplicationContext().getDir("databasedir", Context.MODE_PRIVATE);
        manager = new DatastoreManager(path.getAbsolutePath());
        dbname = "automobiledb";
        try {
            datastore = manager.openDatastore(dbname);
            updateTextView(logView, "Cloudant offline datastore created");
        }
        catch  (Exception e){
            updateTextView(logView, "create datastore failed " + e.getMessage());
        }


        // Get cloudant URI
        try {
            cloudantAPIKey = props.getProperty("cloudantAPIKey");
            cloudantAPIPassword = props.getProperty("cloudantAPIPassword");
            cloudtantAPIUsername = props.getProperty("cloudtantUserName");
            cloudantURI = new URI("https://" + cloudantAPIKey+":"+cloudantAPIPassword+"@"+
                    cloudtantAPIUsername+".cloudant.com/"+ dbname);
            Log.i(CLASS_NAME, "cloudantURI : " + cloudantURI);

        }
        catch (Exception e){

        }

    }

    @Override
    protected void onResume() {
        super.onResume();

        if (push != null) {
            push.listen(notificationListener);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (push != null) {
            push.hold();
        }
    }

    public void createDocument(View view) {

        // Create document body
        Map<String, Object> body = new HashMap<String, Object>();

        body.put("make", "Tesla");
        body.put("model", "Model X");
        body.put("year", 2015);
        body.put("power", "Electric");

        // Create revision and set body
        MutableDocumentRevision revision = new MutableDocumentRevision();
        revision.body = DocumentBodyFactory.create(body);
        try{
            // Save revision to store
            DocumentRevision savedRevision = datastore.createDocumentFromRevision(revision);
            updateTextView(logView, "create Document succeed" + "datastroe documents count: " + datastore.getDocumentCount());
        }
        catch (Exception e){
            updateTextView(logView,"create Document Exception:" + e.toString());
        }

    }

    public void readDocument(View view){
        try {

            updateTextView(logView, "datastore documents count" + datastore.getDocumentCount());
        }
        catch (Exception e){
            updateTextView(logView, e.getMessage());
        }
    }

    public void pullDocument(View view){

        // Create a replicator that replicates changes from the remote
        final Replicator replicator = ReplicatorBuilder.pull().from(cloudantURI).to(datastore).build();

        // Register event listener
        replicator.getEventBus().register(new Object() {

            @Subscribe
            public void complete(ReplicationCompleted event) {
                // Handle ReplicationCompleted event
                updateTextView(logView, event.toString() + "datastore documents count: " + datastore.getDocumentCount());
            }

            @Subscribe
            public void error(ReplicationErrored event) {

                // Handle ReplicationErrored event
                updateTextView(logView,event.errorInfo.toString());
            }


        });

        // Start replication
        replicator.start();

    }

    public void pushDocument(View view){

        // Replicate from the local to remote database
        Replicator replicator = ReplicatorBuilder.push().from(datastore).to(cloudantURI).build();

        // Register event listener
        replicator.getEventBus().register(new Object() {

            @Subscribe
            public void complete(ReplicationCompleted event) {

                // Handle ReplicationCompleted event

                updateTextView(logView,event.toString());
            }

            @Subscribe
            public void error(ReplicationErrored event) {

                // Handle ReplicationErrored event
                updateTextView(logView,event.errorInfo.toString());
            }
        });


        // Fire-and-forget (there are easy ways to monitor the state too)
        replicator.start();
        updateTextView(logView, "replicator:" + replicator.getState().toString());
    }

    void updateTextView(final TextView view, final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                view.setTextSize(12);
                view.setTextColor(Color.RED);
                view.append("\n" + message + "\n");
            }
        });
    }

    void showSimplePushMessage(final MFPSimplePushNotification message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {

                AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                builder.setMessage("Notification Received : "
                        + message);
                builder.setCancelable(true);
                builder.setPositiveButton("OK",
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int s) {
                            }
                        });

                AlertDialog dialog = builder.create();
                dialog.show();

                updateTextView(logView, "Notification Received :" + message);

            }
        });
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
}
