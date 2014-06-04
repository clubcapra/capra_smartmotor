package ca.etsmtl.capra.motors.smartmotor;

import ca.etsmtl.capra.smartmotorlib.Configuration;
import ca.etsmtl.capra.smartmotorlib.listeners.ConnectionListener;
import ca.etsmtl.capra.smartmotorlib.listeners.PositionListener;
import ca.etsmtl.capra.smartmotorlib.robots.Capra6_2;
import ca.etsmtl.capra.smartmotorlib.robots.Robot;
import ca.etsmtl.capra.smartmotorlib.Position;
import geometry_msgs.Quaternion;
import geometry_msgs.Twist;
import capra_msgs.EStopStatus;

import org.ros.concurrent.CancellableLoop;
import org.ros.message.MessageListener;
import org.ros.message.Time;
import org.ros.namespace.GraphName;
import org.ros.node.AbstractNodeMain;
import org.ros.node.ConnectedNode;
import org.ros.node.parameter.ParameterTree;
import org.ros.node.topic.Publisher;
import org.ros.node.topic.Subscriber;
import ca.etsmtl.capra.smartmotorlib.MotorController;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class SmartMotor extends AbstractNodeMain
{
	////////////////////////////////////////////////////////////////////////////////
	////////////////////////////////// Parameters //////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////
	private static String PARAM_NAME_N_MOTORS = "~n_motors";
	private static String PARAM_NAME_PORT_NAME = "~port";
	private static String PARAM_NAME_WATCHDOG_FREQ = "~watchdog_rate";
	private static String PARAM_NAME_COVARIANCE_MATRIX = "~covariance_matrix";
    private static String PARAM_NAME_PUBLISH_RATE = "~publish_rate";
	
	private static String DEFAULT_PORT_NAME = "/dev/ttyUSB1004";
	private static int DEFAULT_N_MOTORS = 2;
	private static int DEFAULT_WATCHDOG_RATE = 5;
    private static int DEFAULT_PUBLISH_RATE = 25;
    private static double[] DEFAULT_COVARIANCE_MATRIX = new double[]{
                                        1.0, 0, 0, 0, 0, 0,  // x
                                        0, 1.0, 0, 0, 0, 0,  // y
                                        0, 0, 1.0, 0, 0, 0,  // z
                                        0, 0, 0, 1.0, 0, 0,  // Rotation on x
                                        0, 0, 0, 0, 1.0, 0,  // Rotation on y
                                        0, 0, 0, 0, 0, 1.0}; // Rotation on z
	
	private int nMotors;
	private String portName;
	private int watchdogFreq = DEFAULT_WATCHDOG_RATE;
    private int publishRate = DEFAULT_PUBLISH_RATE;
	private double[] covarianceMatrix = DEFAULT_COVARIANCE_MATRIX;
	
	ParameterTree params;
	
    ////////////////////////////////////////////////////////////////////////////////
    ///////////////////////////////////// Topics ///////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////
	private static String TOPIC_NAME_ODOM = "odom";
	private static String TOPIC_NAME_CMD_VEL = "~cmd_vel";
    private static String TOPIC_NAME_ESTOP = "~estop";
	
	private Publisher<nav_msgs.Odometry> odomPublisher;
    private Publisher<EStopStatus> eStopPublisher;
	private Subscriber<geometry_msgs.Twist> cmdVelSubscriber;

    ////////////////////////////////////////////////////////////////////////////////
    ///////////////////////////////////// Other ////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////
	private ConnectedNode node;
	private MotorController motors;
	
	private CommandType commandType = CommandType.CMD_VEL;
	
	private float commandedLinearVelocity = 0;
	private float commandedAngularVelocity = 0;
	private long lastVelocityUpdate = 0;

    private Position position = new Position();
    private Semaphore positionSem = new Semaphore(0);
    private long lastPublish = 0;
    private Time lastPositionUpdate;
    
	@Override
	public GraphName getDefaultNodeName()
	{
		return GraphName.of("smartmotor");
	}
	
	private void initTopics()
	{
		odomPublisher = node.newPublisher(TOPIC_NAME_ODOM, nav_msgs.Odometry._TYPE);
		eStopPublisher = node.newPublisher(TOPIC_NAME_ESTOP, EStopStatus._TYPE);

		cmdVelSubscriber = node.newSubscriber(TOPIC_NAME_CMD_VEL, geometry_msgs.Twist._TYPE);
		cmdVelSubscriber.addMessageListener(new cmdVelListener());
	}
	
	private void loadParams ( )
	{
		params = node.getParameterTree();
		
		nMotors = params.getInteger(PARAM_NAME_N_MOTORS, DEFAULT_N_MOTORS);
		portName = params.getString(PARAM_NAME_PORT_NAME, DEFAULT_PORT_NAME);
		watchdogFreq = params.getInteger(PARAM_NAME_WATCHDOG_FREQ, DEFAULT_WATCHDOG_RATE);
		covarianceMatrix = parseCovarianceMatrix(params.getString(PARAM_NAME_COVARIANCE_MATRIX, ""));
        publishRate = params.getInteger(PARAM_NAME_PUBLISH_RATE, DEFAULT_PUBLISH_RATE);
	}

    private double[] parseCovarianceMatrix(String str)
    {
        if ( str == "" )
            return DEFAULT_COVARIANCE_MATRIX;
        else
        {
            String[] strings = str.split(",");
            double[] matrix = new double[strings.length];
            for ( int i = 0; i < strings.length; i ++ )
            {
                matrix[i] = Double.parseDouble(strings[i].trim());
            }
            return matrix;
        }
    }

	private class cmdVelListener implements MessageListener<geometry_msgs.Twist>
	{
		@Override
		public void onNewMessage(Twist cmd_vel) 
		{
			float newLinear = (float)cmd_vel.getLinear().getX();
			float newAngular = (float)cmd_vel.getAngular().getZ();
			
			lastVelocityUpdate = System.currentTimeMillis();
			commandType = CommandType.CMD_VEL;
			
			if ( commandedLinearVelocity != newLinear || commandedAngularVelocity != newAngular )
			{
				commandedLinearVelocity = newLinear;
				commandedAngularVelocity = newAngular;
				motors.setVelocity(commandedLinearVelocity, commandedAngularVelocity);
			}
		}
	}
	
	private void initServices()
	{
		
	}
	
	private boolean connectToMotors()
	{
//        Robot robot = new Capra6_2();
//        Configuration.setRobot(robot);
        Robot robot = Configuration.getRobot();
        robot.setPortName(portName);
        robot.setNbMotors(nMotors);
        robot.setDefaultAccel(10);
        motors = new MotorController();

		return motors.init();
	}
	
	private void initWatchdog ( )
	{
		node.executeCancellableLoop(new CancellableLoop()
		{
			@Override
			protected void loop() throws InterruptedException
			{
                if ( motors.isMoving() && System.currentTimeMillis() - lastVelocityUpdate > 2 * (1000 / watchdogFreq)
					 && commandType == CommandType.CMD_VEL )
				{
					commandedLinearVelocity = 0;
					commandedAngularVelocity = 0;
					motors.setVelocity(commandedLinearVelocity, commandedAngularVelocity);
				}
				
				Thread.sleep(1000 / watchdogFreq);		
			}
		});
	}

    private void initOdomBroadcaster ( )
    {
        node.executeCancellableLoop(new CancellableLoop()
        {
            @Override
            protected void loop() throws InterruptedException
            {
                boolean newPos = positionSem.tryAcquire(10, TimeUnit.MILLISECONDS);
                Time time = node.getCurrentTime();
                long now = System.currentTimeMillis();

                if ( newPos || lastPublish + (1000 / publishRate) <= now )
                {
                    lastPublish = now;
                    nav_msgs.Odometry odom = node.getTopicMessageFactory().newFromType(nav_msgs.Odometry._TYPE);

                    odom.getHeader().setFrameId("odom");
                    odom.setChildFrameId("base_link");
                    if ( newPos && lastPositionUpdate != null)
                        time = lastPositionUpdate;
                    odom.getHeader().setStamp(time);

                    // Position
                    geometry_msgs.Point pos = odom.getPose().getPose().getPosition();
                    pos.setX(position.getX());
                    pos.setY(position.getY());
                    pos.setZ(0);

                    // Orientation
                    Quaternion quaternion = QuaternionUtils.createQuaternionMsgFromYaw(node, position.getYaw());
                    odom.getPose().getPose().setOrientation(quaternion);

                    // Velocity
                    if ( newPos )
                    {
                        odom.getTwist().getTwist().getLinear().setX(position.getLinearVelocity());
                        odom.getTwist().getTwist().getAngular().setZ(position.getAngularVelocity());
                    }

                    // Covariance

                    odom.getPose().setCovariance(covarianceMatrix);
                    odom.getTwist().setCovariance(covarianceMatrix);

                    odomPublisher.publish(odom);
                }
            }
        });
    }

    private void publishEStopStatus(boolean stopped)
    {
        EStopStatus msg = node.getTopicMessageFactory().newFromType(EStopStatus._TYPE);
        msg.setStopped(stopped);
        eStopPublisher.publish(msg);
    }
	
	private void initListeners()
	{
        motors.setPositionListener(new PositionListener() {
            @Override
            public void onNewPosition(Position pos, long timestamp) {
                position = pos;
                positionSem.release();
                lastPositionUpdate = node.getCurrentTime();
            }
        });

        motors.setConnectionlistener(new ConnectionListener() {
            @Override
            public void onConnect()
            {
                publishEStopStatus(false);
            }

            @Override
            public void onDisconnect()
            {
                publishEStopStatus(true);
            }
        });
	}

	private void init ( )
	{
		initTopics();
		initServices();
		initWatchdog();
		initListeners();
        initOdomBroadcaster();
	}
	
	@Override
	public void onStart(final ConnectedNode connectedNode)
	{
		node = connectedNode;
		loadParams();
		
		if ( connectToMotors() )
			init();
		else 
			throw new RuntimeException("Error connecting to the motors");
	}
}