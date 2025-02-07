package org.jenkinsci.plugins.p4.client;

import com.perforce.p4java.exception.AccessException;
import com.perforce.p4java.exception.ConnectionException;
import com.perforce.p4java.exception.P4JavaException;
import com.perforce.p4java.exception.RequestException;
import com.perforce.p4java.impl.mapbased.server.Server;
import com.perforce.p4java.impl.mapbased.server.cmd.ResultMapParser;
import com.perforce.p4java.server.CmdSpec;
import com.perforce.p4java.server.IOptionsServer;
import com.perforce.p4java.server.callback.ICommandCallback;
import com.perforce.p4java.server.callback.IProgressCallback;
import hudson.AbortException;
import hudson.model.Descriptor;
import hudson.model.TaskListener;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.p4.PerforceScm;
import org.jenkinsci.plugins.p4.console.P4Logging;
import org.jenkinsci.plugins.p4.console.P4Progress;
import org.jenkinsci.plugins.p4.credentials.P4BaseCredentials;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static com.perforce.p4java.common.base.ObjectUtils.nonNull;

public class SessionHelper extends CredentialsHelper {

	private static Logger logger = Logger.getLogger(SessionHelper.class.getName());

	private final ConnectionConfig connectionConfig;
	private final Validate validate;
	private final long sessionLife;
	private final boolean sessionEnabled;

	private IOptionsServer connection;
	private boolean abort = false;

	private static Map<String, Long> loginCache = new HashMap<>();

	public SessionHelper(P4BaseCredentials credential, TaskListener listener) throws IOException {
		super(credential, listener);
		this.connectionConfig = new ConnectionConfig(getCredential());
		this.sessionLife = credential.getSessionLife();
		this.sessionEnabled = credential.isSessionEnabled();
		connectionRetry();
		validate = new Validate(listener);
	}

	public SessionHelper(String credentialID, TaskListener listener) throws IOException {
		super(credentialID, listener);
		this.connectionConfig = new ConnectionConfig(getCredential());
		this.sessionLife = getCredential().getSessionLife();
		this.sessionEnabled = getCredential().isSessionEnabled();
		connectionRetry();
		validate = new Validate(listener);
	}

	public static void invalidateUser(String user) {
		loginCache.remove(user);
	}

	public IOptionsServer getConnection() {
		return connection;
	}

	public Validate getValidate() {
		return validate;
	}

	public String getTrust() throws Exception {
		return connection.getTrust();
	}

	public String getTicket() {
		try {
			if (login()) {
				return connection.getAuthTicket();
			}
		} catch (Exception e) {
			log(e.getLocalizedMessage());
		}
		return null;
	}

	public boolean isConnected() {
		if (connection == null) {
			return false;
		}
		return connection.isConnected();
	}

	public boolean isUnicode() throws ConnectionException, AccessException, RequestException {
		return connection.supportsUnicode();
	}

	/**
	 * Checks the Perforce server version number and returns true if greater
	 * than or equal to the min version. The value of min must be of the form
	 * 20092 or 20073 (corresponding to 2009.2 and 2007.3 respectively).
	 *
	 * @param min Minimum server version
	 * @return true if version supported.
	 */
	public boolean checkVersion(int min) {
		int ver = connection.getServerVersionNumber();
		return (ver >= min);
	}

	public boolean login() throws Exception {
		connection.setUserName(getAuthorisationConfig().getUsername());

		// CHARSET is not defined (only for client access)
		if (isUnicode()) {
			connection.setCharsetName("utf8");
		}

		// Exit early if logged in
		if (isLogin()) {
			return true;
		}

		switch (getAuthorisationConfig().getType()) {
			case PASSWORD:
				String pass = getAuthorisationConfig().getPassword();
				boolean allHosts = getAuthorisationConfig().isAllhosts();
				connection.login(pass, allHosts);
				break;

			case TICKET:
				String ticket = getAuthorisationConfig().getTicketValue();
				connection.setAuthTicket(ticket);
				break;

			case TICKETPATH:
				String path = getAuthorisationConfig().getTicketPath();
				if (path == null || path.isEmpty()) {
					path = connection.getTicketsFilePath();
				}
				connection.setTicketsFilePath(path);
				break;

			default:
				throw new Exception("Unknown Authorisation type: " + getAuthorisationConfig().getType());
		}

		// return login status...
		if (isLogin()) {
			return true;
		} else {
			String status = connection.getLoginStatus();
			logger.info("P4: login failed '" + status + "'");
			return false;
		}
	}

	public void logout() throws Exception {
		if (isLogin()) {
			connection.logout();
		}
	}

	/**
	 * Disconnect from the Perforce Server.
	 */
	protected void disconnect() {
		try {
			getConnection().disconnect();
			logger.fine("P4: closed connection OK");
		} catch (Exception e) {
			String err = "P4: Unable to close Perforce connection.";
			logger.severe(err);
			log(err);
		}
	}

	public boolean hasAborted() {
		return abort;
	}

	public void abort() {
		this.abort = true;
	}

	public PerforceScm.DescriptorImpl getP4SCM() {
		Jenkins j = Jenkins.getInstance();
		if (j != null) {
			Descriptor dsc = j.getDescriptor(PerforceScm.class);
			if (dsc instanceof PerforceScm.DescriptorImpl) {
				PerforceScm.DescriptorImpl p4scm = (PerforceScm.DescriptorImpl) dsc;
				return p4scm;
			}
		}
		return null;
	}

	/**
	 * Retry Connection with back off for each failed attempt.
	 */
	private void connectionRetry() throws AbortException {

		int trys = 0;
		int attempt = getRetry();
		String err = "P4: Invalid credentials. Giving up...";

		while (trys <= attempt) {
			try {
				if (connect()) {
					return;
				}
			} catch (Exception e) {
				err = e.getMessage();
			}
			trys++;
			String msg = "P4: Connection retry: " + trys;
			logger.severe(msg);
			log(msg);

			// back off n^2 seconds, before retry
			try {
				TimeUnit.SECONDS.sleep(trys ^ 2);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}

		logger.severe(err);
		log(err);
		throw new AbortException(err);
	}

	/**
	 * Convenience wrapper to connect and report errors
	 */
	private boolean connect() throws Exception {
		// Connect to the Perforce server
		this.connection = ConnectionFactory.getConnection(connectionConfig);
		logger.fine("P4: opened connection OK");

		// Login to Perforce
		try {
			login();
		} catch (Exception e) {
			String err = "P4: Unable to login: " + e;
			logger.severe(err);
			log(err);
			return false;
		}

		// Register progress callback
		IProgressCallback progress = new P4Progress(getListener(), this);
		this.connection.registerProgressCallback(progress);

		// Register logging callback
		ICommandCallback logging = new P4Logging(getListener(), false);
		this.connection.registerCallback(logging);

		// Check P4IGNORE Environment
		Server server = (Server) this.connection;
		if (server.getIgnoreFileName() == null) {
			String os = System.getProperty("os.name").toLowerCase();
			String ignore = os.contains("win") ? "p4ignore.txt" : ".p4ignore";
			server.setIgnoreFileName(ignore);
		}

		return true;
	}

	private boolean isLogin() throws Exception {
		String user = connection.getUserName();
		if (sessionEnabled && loginCache.containsKey(user)) {
			long expire = loginCache.get(user);
			long remain = expire - System.currentTimeMillis() - sessionLife;
			if (remain > 0) {
				logger.info("Found session entry for: " + user);
				return true;
			} else {
				logger.info("Removing session entry for: " + user);
				loginCache.remove(user);
			}
		}

		logger.info("No entry in session for: " + user);
		List<Map<String, Object>> resultMaps = connection.execMapCmdList(CmdSpec.LOGIN, new String[]{"-s"}, null);

		if (nonNull(resultMaps) && !resultMaps.isEmpty()) {
			for (Map<String, Object> map : resultMaps) {
				String status = ResultMapParser.getInfoStr(map);
				if (status == null) {
					continue;
				}
				if (status.contains("not necessary")) {
					loginCache.put(user, Long.MAX_VALUE);
					return true;
				}
				if (status.contains("ticket expires in")) {
					loginCache.put(user, getExpiry(status));
					return true;
				}
				// If there is a broker or something else that swallows the message
				if (status.isEmpty()) {
					return true;
				}
			}
		}
		return false;
	}

	private long getExpiry(String loginStatus) throws P4JavaException {
		try {
			String pattern = ".* expires in (\\d+) hours (\\d+) minutes.";
			Pattern regex = Pattern.compile(pattern);
			Matcher matcher = regex.matcher(loginStatus);

			if (matcher.matches()) {
				String hourStr = matcher.group(1);
				String minStr = matcher.group(2);
				int hours = Integer.parseInt(hourStr);
				int minutes = Integer.parseInt(minStr);
				long milli = ((hours * 60L * 60L) + (minutes * 60L)) * 1000L;
				return System.currentTimeMillis() + milli;
			}
			throw new P4JavaException("Unable to parse expires time: " + loginStatus);
		} catch (PatternSyntaxException | NumberFormatException e) {
			throw new P4JavaException(e);
		}
	}

}
