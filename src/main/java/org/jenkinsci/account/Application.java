package org.jenkinsci.account;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.net.InetAddresses;
import jiraldapsyncer.JiraLdapSyncer;
import jiraldapsyncer.ServiceLocator;
import org.apache.commons.collections.EnumerationUtils;
import org.jenkinsci.account.openid.JenkinsOpenIDServer;
import org.kohsuke.stapler.*;
import org.kohsuke.stapler.config.ConfigurationLoader;
import org.kohsuke.stopforumspam.Answer;
import org.kohsuke.stopforumspam.StopForumSpam;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.mail.Message.RecipientType;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.naming.AuthenticationException;
import javax.naming.Context;
import javax.naming.NameAlreadyBoundException;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.AttributeInUseException;
import javax.naming.directory.Attributes;
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.LdapContext;
import javax.net.ssl.HttpsURLConnection;
import javax.servlet.http.Cookie;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.rmi.RemoteException;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import static javax.naming.directory.DirContext.*;
import static javax.naming.directory.SearchControls.SUBTREE_SCOPE;
import static org.apache.commons.lang.StringUtils.isEmpty;
import static org.jenkinsci.account.LdapAbuse.*;

/**
 * Root of the account application.
 *
 * @author Kohsuke Kawaguchi
 */
public class Application {
    /**
     * Configuration parameter.
     */
    private final Parameters params;

    /**
     * For bringing the OpenID server into the URL space.
     */
    public final JenkinsOpenIDServer openid;

    // not exposing this to UI
    /*package*/ final CircuitBreaker circuitBreaker;

    public Application(Parameters params) throws IOException {
        this.params = params;
        this.openid = new JenkinsOpenIDServer(this);
        this.circuitBreaker = new CircuitBreaker(params);
    }

    public Application(Properties config) throws IOException {
        this(ConfigurationLoader.from(config).as(Parameters.class));
    }

    public Application(File config) throws IOException {
        this(ConfigurationLoader.from(config).as(Parameters.class));
    }

    public String captchaPublicKey() {
        return params.recaptchaPublicKey();
    }

    public String getUrl() {
        return params.url();
    }

    /**
     * Receives the sign-up form submission.
     */
    public HttpResponse doDoSignup(
            StaplerRequest request,
            StaplerResponse response,
            @QueryParameter String userid,
            @QueryParameter String firstName,
            @QueryParameter String lastName,
            @QueryParameter String email,
            @QueryParameter String usedFor,
            @QueryParameter String hp,
            @Header("X-Forwarded-For") String ip    // client IP
    ) throws Exception {

        ip = extractFirst(ip);

        String uresponse = request.getParameter("g-recaptcha-response");
        if (uresponse==null)    throw new Error("captcha missing");

        if (!verifyCaptcha(uresponse, ip)) {
            throw new UserError("Captcha mismatch. Please try again and retry a captcha to prove that you are a human");
        }

        userid = userid.toLowerCase();

        if (!VALID_ID.matcher(userid).matches())
            throw new UserError("Invalid user name: "+userid);

        if (isEmpty(firstName))
            throw new UserError("First name is required");
        if (isEmpty(lastName))
            throw new UserError("First name is required");
        if (isEmpty(email))
            throw new UserError("e-mail is required");
        if(!email.contains("@"))
            throw new UserError("Need a valid e-mail address.");
        if(isEmpty(usedFor))
            throw new UserError("Please fill what you use Jenkins for.");

        List<String> blockReasons = new ArrayList<String>();
        if(!isEmpty(hp))
            blockReasons.add("Honeypot: " + hp);

        if(Pattern.matches("(?:^jb\\d+@gmail.com|seo\\d+@)", email)) {
            blockReasons.add("BL: email (custom)");
        }

        for (String fragment : USERID_BLACKLIST) {
            if(userid.contains(fragment)) {
                blockReasons.add("BL: userid");
            }
        }

        for (String fragment : IP_BLACKLIST) {
            if(ip.startsWith(fragment)) {
                blockReasons.add("BL: IP");
            }
        }
        // domain black list
        String lm = email.toLowerCase(Locale.ENGLISH);
        for (String fragment : EMAIL_BLACKLIST) {
            if (lm.contains(fragment.toLowerCase()))
                blockReasons.add("BL: email");
        }

        for(String fragment : USE_BLACKLIST) {
            if(usedFor != null && !usedFor.trim().isEmpty()) {
                if (usedFor.trim().equalsIgnoreCase(fragment)) {
                    blockReasons.add("BL: use");
                }
            }
        }

        if(badNameElement(usedFor)) {
            blockReasons.add("Garbled use");
        }

        if(userid.equalsIgnoreCase(usedFor) || firstName.equalsIgnoreCase(usedFor) || lastName.equalsIgnoreCase(usedFor) || email.equalsIgnoreCase(usedFor)) {
            blockReasons.add("Use same as name");
        }

        if(badNameElement(userid) || badNameElement(firstName) || badNameElement(lastName)) {
            blockReasons.add("Bad name element");
        }

        final DirContext con = connect();
        try {
            if(ldapObjectExists(con, "(id={0})", userid)) {
                throw new UserError("ID " + userid + " is already taken. Perhaps you already have an account imported from legacy java.net? You may try resetting the password.");
            }
            if(ldapObjectExists(con, "(mail={0})", email)) {
                blockReasons.add("Existing email in system");
            }
        } finally {
            con.close();
        }

        if(checkCookie(request, ALREADY_SIGNED_UP)) {
            blockReasons.add("Cookie");
        }

        if(circuitBreaker.check()) {
            blockReasons.add("circuit breaker");
        }

        // spam check
        // TODO, update StopForumSpam to add &notorexit (See http://www.stopforumspam.com/usage)
        // TODO, add in http://fspamlist.com/ as well
        for (Answer a : new StopForumSpam().build().ip(ip).email(email).query()) {
            if (a.isAppears()) {
                blockReasons.add("Stopforumspam: " + a.toString());
            }
        }

        // IP Reputation Checks

        String reversedIp = Joiner.on(".").join(Lists.reverse(Arrays.asList(ip.split("\\."))));
        for(String rblHost : Arrays.asList("rbl.megarbl.net", "zen.spamhaus.org")) {
            for (String txt : getTxtRecord(reversedIp + "." + rblHost)) {
                blockReasons.add("RBL " + rblHost + ": " + txt);
            }
        }

        String userDetails = userDetails(userid, firstName, lastName, email, ip, usedFor);
        if(blockReasons.size() > 0) {
            String body = "Rejecting, likely spam:\n\n" + userDetails + "\n\nHTTP Headers\n" +
                dumpHeaders(request) + "\n\n" +
                "===Block Reasons===\n"
                + Joiner.on("\n").join(blockReasons) + "\n===================" + "\n\n" +
                "IP Void link: http://ipvoid.com/scan/" + ip + "\n\n" +
                "To allow this account to be created, click the following link:\n" +
                "https://jenkins-ci.org/account/admin/signup?userId=" + enc(userid) + "&firstName=" + enc(firstName) + "&lastName=" + enc(lastName) + "&email=" + enc(email) + "\n";
            mail("Admin <admin@jenkins-ci.org>", "jenkinsci-account-admins@googlegroups.com", "Rejection of a new account creation for " + firstName + " " + lastName, body, "text/plain");
            throw new UserError(SPAM_MESSAGE);
        }

        String password = createRecord(userid, firstName, lastName, email);
        LOGGER.info("User "+userid+" is from "+ip);
        mail("Admin <admin@jenkins-ci.org>", "jenkinsci-account-admins@googlegroups.com", "New user created for " + userid,
            userDetails + "\n\nHTTP Headers\n" +
                dumpHeaders(request) + "\n\n" + "Account page: https://jenkins-ci.org/account/admin/search?word=" + userid + "\n\nIP Void link: http://ipvoid.com/scan/" + ip + "/\n", "text/plain");
        new User(userid,email).mailPassword(password);

        Cookie cookie = new Cookie(ALREADY_SIGNED_UP, "1");
        cookie.setDomain("jenkins-ci.org");
        cookie.setPath("/account");
        cookie.setMaxAge(24 * 60 * 60);
        response.addCookie(cookie);

        return new HttpRedirect("doneMail");
    }

    private String dumpHeaders(StaplerRequest request) {
        Enumeration headerNames = request.getHeaderNames();
        StringBuffer buffer = new StringBuffer();
        while(headerNames.hasMoreElements()) {
            String headerName = (String)headerNames.nextElement();
            buffer.append(headerName).append("=").append(request.getHeader(headerName)).append("\n");
        }
        return buffer.toString();
    }

    private boolean badNameElement(String userid) {
        return Pattern.matches("^[sdfghrtbvd0-9]+$", userid.toLowerCase());
    }

    public String geoIp(String ip) {
        try {
            URL url = new URL("http://freegeoip.net/csv/" + ip);
            BufferedReader reader = new BufferedReader( new InputStreamReader(url.openStream()));
            return reader.readLine();
        } catch (MalformedURLException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return "";
    }

    public List<String> getTxtRecord(String hostName) {

        Hashtable<String, String> env = new Hashtable<String, String>();
        env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        List<String> txtRecords = new ArrayList<String>();
        try {
            DirContext dirContext = new InitialDirContext(env);
            Attributes attrs = dirContext.getAttributes(hostName, new String[] { "TXT" });
            Attribute attr = attrs.get("TXT");
            for(Object txt: EnumerationUtils.toList(attr.getAll())) {
                if(txt != null) {
                    txtRecords.add(txt.toString());
                }
            }
            return txtRecords;
        } catch (javax.naming.NamingException e) {
            e.printStackTrace();
            return txtRecords;
        }
    }

    private boolean verifyCaptcha(String uresponse, String ip) {
        String postParams = "secret=" + URLEncoder.encode(params.recaptchaPrivateKey()) +
                           "&remoteip=" + URLEncoder.encode(ip) +
                           "&response=" + URLEncoder.encode(uresponse);
        try {
            URL obj = new URL("https://www.google.com/recaptcha/api/siteverify");
            HttpsURLConnection conn = (HttpsURLConnection) obj.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.5");
            conn.setDoOutput(true);
            DataOutputStream wr = new DataOutputStream(conn.getOutputStream());
            wr.writeBytes(postParams);
            wr.flush();
            wr.close();

            int responseCode = conn.getResponseCode();

            BufferedReader in = new BufferedReader(new InputStreamReader(
                    conn.getInputStream()));
            String inputLine;
            StringBuffer response = new StringBuffer();

            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
            }
            in.close();

            //parse JSON response and return 'success' value
            JsonReader jsonReader = Json.createReader(new StringReader(response.toString()));
            JsonObject jsonObject = jsonReader.readObject();
            jsonReader.close();
            return jsonObject.getBoolean("success");
        } catch (MalformedURLException e) {
            e.printStackTrace();
            return false;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    private boolean checkCookie(StaplerRequest request, String x) {
        for (Cookie cookie: request.getCookies()) {
            if(cookie.getName().equals(ALREADY_SIGNED_UP)) {
                return "1".equals(cookie.getValue());
            }
        }
        return false;
    }

    private void mail(String from, String to, String subject, String body, String encoding) throws MessagingException {
        Session s = createJavaMailSession();
        MimeMessage msg = new MimeMessage(s);
        msg.setSubject(subject);
        msg.setFrom(new InternetAddress(from));
        msg.setRecipient(RecipientType.TO, new InternetAddress(to));
        msg.setContent(body, encoding);
        Transport.send(msg);
    }

    private String userDetails(String userid, String firstName, String lastName, String email, String ip, String usedFor) {
        return String.format(
            "ip=%s\nemail=%s\nuserId=%s\nlastName=%s\nfirstName=%s\nuse=%s\nGeoIp=%s",
            ip, email, userid, lastName, firstName, usedFor, geoIp(ip));
    }

    /**
     * If IP consists of multiple tokens, like "1.2.3.4, 5.6.7.8" then just extract the first one.
     */
    private String extractFirst(String ip) {
        if (ip==null)   return "127.0.0.1";
        int idx = ip.indexOf(",");
        if(idx>0) {
            for (String xForwardedFor : ip.split(",")) {
                if (!Strings.isNullOrEmpty(xForwardedFor) && !InetAddresses.forString(xForwardedFor).isSiteLocalAddress()) {
                    return ip;
                }
            }
        }
        return ip;
    }

    private static String enc(String s) throws UnsupportedEncodingException {
        return URLEncoder.encode(s,"UTF-8");
    }

    /**
     * Adds the new user entry to LDAP.
     */
    public String createRecord(String userid, String firstName, String lastName, String email) throws NamingException {
        Attributes attrs = new BasicAttributes();
        attrs.put("objectClass", "inetOrgPerson");
        attrs.put("givenName", firstName);
        attrs.put("sn", lastName);
        attrs.put("mail", email);
        String password = PasswordUtil.generateRandomPassword();
        attrs.put("userPassword", PasswordUtil.hashPassword(password));
        attrs.put(REGISTRATION_DATE, new SimpleDateFormat("yyyy/MM/dd HH:mm:ss").format(new Date()));
        attrs.put(SENIOR_STATUS, "N");

        final DirContext con = connect();
        try {
            String fullDN = "cn=" + userid + "," + params.newUserBaseDN();
            con.createSubcontext(fullDN, attrs).close();

            // add to the right group
            try {
                con.modifyAttributes("cn=all,ou=groups,dc=jenkins-ci,dc=org",ADD_ATTRIBUTE,new BasicAttributes("member",fullDN));
            } catch (AttributeInUseException e) {
                // deletes and re-add it to make the case match
                con.modifyAttributes("cn=all,ou=groups,dc=jenkins-ci,dc=org",REMOVE_ATTRIBUTE,new BasicAttributes("member",fullDN));
                con.modifyAttributes("cn=all,ou=groups,dc=jenkins-ci,dc=org",ADD_ATTRIBUTE,new BasicAttributes("member",fullDN));
            }
        } catch (NameAlreadyBoundException e) {
            throw new UserError("ID "+userid+" is already taken. Perhaps you already have an account imported from legacy java.net? You may try resetting the password.");
        } finally {
            con.close();
        }

        try {
            JiraLdapSyncer jiraLdapSyncer = (JiraLdapSyncer) new ServiceLocator().lookupService(JiraLdapSyncer.ROLE);
            jiraLdapSyncer.syncOneUserFromLDAPToJIRA(userid);
        } catch (RemoteException e) {
            LOGGER.log(Level.SEVERE, "Failed to register " + userid + " to JIRA", e);
        }

        LOGGER.info("User "+userid+" signed up: "+email);
        return password;
    }

    private boolean ldapObjectExists(DirContext con, String filterExpr, Object filterArgs) throws NamingException {
        return con.search(params.newUserBaseDN(), filterExpr, new Object[]{filterArgs}, new SearchControls()).hasMore();
    }

    /**
     * Handles the password reset form submission.
     */
    public HttpResponse doDoPasswordReset(@QueryParameter String id) throws Exception {
        final DirContext con = connect();
        try {
            Iterator<User> a = searchByWord(id, con);
            if (!a.hasNext())
                throw new UserError("No such user account found: "+id);

            User u = a.next();

            String p = PasswordUtil.generateRandomPassword();
            u.modifyPassword(con, p);
            u.mailPassword(p);
        } finally {
            con.close();
        }

        return new HttpRedirect("doneMail");
    }

    public User getUserById(String id, DirContext con) throws NamingException {
        String dn = "cn=" + id + "," + params.newUserBaseDN();
        return new User(con.getAttributes(dn));
    }

    /**
     * Object that represents some user in LDAP.
     */
    public class User {
        /**
         * User ID, such as 'kohsuke'
         */
        public final String id;
        /**
         * E-mail address.
         */
        public final String mail;

        public User(String id, String mail) {
            this.id = id;
            this.mail = mail;
        }

        public User(Attributes att) throws NamingException {
            id = (String) att.get("cn").get();
            mail = (String) att.get("mail").get();
        }

        public String getDn() {
            return String.format("cn=%s,%s", id, params.newUserBaseDN());
        }

        public void modifyPassword(DirContext con, String password) throws NamingException {
            con.modifyAttributes(getDn(),REPLACE_ATTRIBUTE,new BasicAttributes("userPassword",PasswordUtil.hashPassword(password)));
            LOGGER.info("User "+id+" reset the password: "+mail);
        }

        public void modifyEmail(DirContext con, String email) throws NamingException {
            con.modifyAttributes(getDn(),REPLACE_ATTRIBUTE,new BasicAttributes("mail",email));
            LOGGER.info("User "+id+" reset the e-mail address to: "+email);
        }

        /**
         * Sends a new password to this user.
         */
        public void mailPassword(String password) throws MessagingException {
            mail("Admin <admin@jenkins-ci.org>", mail, "Your access to jenkins-ci.org", "Your userid is " + id + "\n" +
                "Your temporary password is " + password + " \n" +
                "\n" +
                "Please visit http://jenkins-ci.org/account and update your password and profile\n", "text/plain");
        }

        public void delete(DirContext con) throws NamingException {
            con.destroySubcontext(getDn());
            LOGGER.info("User " + id + " deleted");
        }
    }

    private Session createJavaMailSession() {
        Properties props = new Properties(System.getProperties());
        props.put("mail.smtp.host",params.smtpServer());
        return Session.getInstance(props);
    }

    /**
     * Search LDAP with the given keyword, returning matching users.
     */
    public Iterator<User> searchByWord(String idOrMail, DirContext con) throws NamingException {
        final NamingEnumeration<SearchResult> e = con.search(params.newUserBaseDN(), "(|(mail={0})(cn={0}))", new Object[]{idOrMail}, new SearchControls());
        return new Iterator<User>() {
            public boolean hasNext() {
                return e.hasMoreElements();
            }

            public User next() {
                try {
                    return new User(e.nextElement().getAttributes());
                } catch (NamingException x) {
                    throw new RuntimeException(x);
                }
            }

            public void remove() {
                throw new UnsupportedOperationException();
            }
        };
    }

    public LdapContext connect() throws NamingException {
        return connect(params.managerDN(), params.managerPassword());
    }

    public LdapContext connect(String dn, String password) throws NamingException {
        Hashtable<String,String> env = new Hashtable<String,String>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, params.server());
        env.put(Context.SECURITY_PRINCIPAL, dn);
        env.put(Context.SECURITY_CREDENTIALS, password);
        return new InitialLdapContext(env, null);
    }

    /**
     * Handles the login form submission.
     */
    public HttpResponse doDoLogin(
            @QueryParameter String userid,
            @QueryParameter String password,
            @QueryParameter String from
    ) throws Exception {
        if (userid==null || password==null)
            throw new UserError("Missing credential");

        String dn = "cn=" + userid + "," + params.newUserBaseDN();
        try {
            LdapContext context = connect(dn, password);    // make sure the password is valid
            try {
                Stapler.getCurrentRequest().getSession().setAttribute(Myself.class.getName(),
                        new Myself(this, dn, context.getAttributes(dn), getGroups(dn,context)));
            } finally {
                context.close();
            }
        } catch (AuthenticationException e) {
            throw new UserError(e.getMessage());
        }

        // to limit the redirect to this application, require that the from URL starts from '/'
        if (from==null || !from.startsWith("/")) from="/myself/";
        return HttpResponses.redirectTo(from);
    }

    /**
     * Obtains the group of the user specified by the given DN.
     */
    Set<String> getGroups(String dn, LdapContext context) throws NamingException {
        Set<String> groups = new HashSet<String>();
        SearchControls c = new SearchControls();
        c.setReturningAttributes(new String[]{"cn"});
        c.setSearchScope(SUBTREE_SCOPE);
        NamingEnumeration<SearchResult> e = context.search("dc=jenkins-ci,dc=org", "(& (objectClass=groupOfNames) (member={0}))", new Object[]{dn}, c);
        while (e.hasMore()) {
            groups.add(e.nextElement().getAttributes().get("cn").get().toString());
        }
        return groups;
    }

    public HttpResponse doLogout(StaplerRequest req) {
        req.getSession().invalidate();
        return HttpResponses.redirectToDot();
    }

    public boolean isLoggedIn() {
        return current() !=null;
    }

    public boolean isAdmin() {
        Myself myself = current();
        return myself!=null && myself.isAdmin();
    }

    /**
     * If the user has already logged in, retrieve the current user, otherwise
     * send the user to the login page.
     */
    public Myself getMyself() {
        Myself myself = current();
        if (myself==null) {
            // needs to login
            StaplerRequest req = Stapler.getCurrentRequest();
            StringBuilder from = new StringBuilder(req.getRequestURI());
            if (req.getQueryString()!=null)
                from.append('?').append(req.getQueryString());
            try {
                throw HttpResponses.redirectViaContextPath("login?from="+ URLEncoder.encode(from.toString(),"UTF-8"));
            } catch (UnsupportedEncodingException e) {
                throw new AssertionError(e);
            }
        }
        return myself;
    }

    /**
     * This is a test endpoint to make sure the reverse proxy forwarding is working.
     */
    public HttpResponse doForwardTest(@Header("X-Forwarded-For") String header) {
        return HttpResponses.plainText(header);
    }

    private Myself current() {
        return (Myself) Stapler.getCurrentRequest().getSession().getAttribute(Myself.class.getName());
    }

    public AdminUI getAdmin() {
        return getMyself().isAdmin() ? new AdminUI(this) : null;
    }

    private static final Logger LOGGER = Logger.getLogger(Application.class.getName());

    private static final Pattern VALID_ID = Pattern.compile("[a-z0-9_]+");

    public static final List<String> EMAIL_BLACKLIST = Arrays.asList(
        // TODO: Integrate with https://github.com/wesbos/burner-email-providers
        // Also can use this to check if disposable service: http://www.block-disposable-email.com/cms/try/
        "@anappthat.com",
        "@boximail.com",
        "@clrmail.com",
        "@dodsi.com",
        "@eelmail.com",
        "@getairmail.com",
        "@grandmamail.com",
        "@grandmasmail.com",
        "@guerrillamail.com",
        "@imgof.com",
        "@mailcatch.com",
        "@maildx.com",
        "@mailinator.com",
        "@mailnesia.com",
        "@rediffmail.com",
        "@sharklasers.com",
        "@thrma.com",
        "@tryalert.com",
        "@vomoto.com",
        "@webtrackker.com",
        "@yahoo.co.id",
        "@yeslifecyclemarketing.com",
        "@yopmail.com",
        "@zetmail.com",
        "abdhesh090@gmail.com",
        "abdheshnir.vipra@gmail.com",
        "adreahilton@gmail.com",
        "ajayrudelee@gmail.com",
        "ajymaansingh@gmail.com",
        "albertthomas",
        "AndentspouRita@gmail.com",
        "andorclifs@gmail.com",
        "andrusmith",
        "angthpofphilip@gmail.com",
        "anilkandpal0@gmail.com",
        "anilsingh7885945@gmail.com",
        "ankit",
        "apwebs7012@yahoo.com",
        "apwebs70@gmail.com",
        "arena.wilson91@gmail.com",
        "ashishkumar",
        "ashwanikumar",
        "balramchoudhary.ballu@gmail.com",
        "bcmdsbncskj@yandex.com",
        "besto.sty@yandex.com",
        "bidupan12@gmail.com",
        "billydoch021@gmail.com",
        "boleshahuja88@gmail.com",
        "choutpoyjenniferm@gmail.com",
        "ciodsjiocxjosa@yandex.com",
        "ClarencePatterson570@gmail.com",
        "cooperdavidd@gmail.com",
        "crsgroupindia@gmail.com",
        "dasdasdsas32@gmail.com",
        "deepakkumar02singh@gmail.com",
        "dersttycert101@gmail.com",
        "donallakarpissaa@gmail.com",
        "dr74402@gmail.com",
        "drruytuyj@gmail.com",
        "dutchess.meethi@gmail.com",
        "ethanluna635@gmail.com",
        "fifixtpoqpatrickh@gmail.com",
        "FishepoeMary@gmail.com",
        "flustpozwilliamp@gmail.com",
        "folk.zin87@gmail.com",
        "fragendpotmauriciok@gmail.com",
        "GallifingspoyJoannel@gmail.com",
        "gamblerbhaijaan@gmail.com",
        "georgegallego.com@gmail.com",
        "georgiaaby@gmail.com",
        "HatteroublepocMartha@gmail.com",
        "Hauptnuo214@gmail.com",
        "hcuiodsciodso@yandex.com",
        "HenryMullins",
        "HerstpopEnriqued@gmail.com",
        "himeshsinghiq@gmail.com",
        "hipearspodarthurd@gmail.com",
        "hontpojpatricia",
        "HounchpowJohn@gmail.com",
        "HowerpofHarold@gmail.com",
        "hpprinter",
        "hrrbanga",
        "hsharish",
        "huin.lisko097@gmail.com",
        "ik96550@gmail.com",
        "intelomedia02@gmail.com",
        "intuitphonenumber",
        "iqinfotech",
        "iscopedigital11@gmail.com",
        "italygroupspecialist@gmail.com",
        "Jamersonnvy309@gmail.com",
        "janes6521@gmail.com",
        "janessmith",
        "jayshown81@gmail.com",
        "jecksonmike1512@gmail.com",
        "Jeevikasraj@gmail.com",
        "jhonsinha",
        "jim.cook2681@gmail.com",
        "jksadnhk@gmail.com",
        "jmike7162@gmail.com",
        "johngarry227@gmail.com",
        "johnmaclan1@gmail.com",
        "johnmatty55@gmail.com",
        "JohnnyColvin428@gmail.com",
        "johnsinha",
        "johnydeep0712@gmail.com",
        "kalidass34212@gmail.com",
        "kaveribhardwaj@outlook.com",
        "kk+spamtest@kohsuke.org",
        "kripalsingh446@gmail.com",
        "krishgail30@yahoo.com",
        "kumar.raghavendra84@gmail.com",
        "kumar.uma420@gmail.com",
        "kumarprem",
        "kumarsujit",
        "laptoprepair",
        "LarrySilva",
        "litagray931@gmail.com",
        "litawilliam36@gmail.com",
        "loksabha100@gmail.com",
        "lovebhaiseo@gmail.com",
        "lutherorea2807@gmail.com",
        "mac2help@outlook.com",
        "mac2help@outlook.com",
        "macden",
        "madeleineforsyth290@gmail.com",
        "manasfirdose",
        "maohinseeeeeee@outlook.com",
        "masmartin71@gmail.com",
        "mehrabharat137@gmail.com",
        "michelbavan@gmail.com",
        "mmmarsh12@gmail.com",
        "mohandaerer",
        "mohankeeded",
        "morrisonjohn293@gmail.com",
        "msofficeservices13@gmail.com",
        "nalspoibarbarab@gmail.com",
        "ncrpoo",
        "ncrraanisapna@gmail.com",
        "ncrrohit",
        "ncrsona",
        "nishanoor32",
        "obat@",
        "OllouretypotIda@gmail.com",
        "omprakash",
        "outlookhelpline",
        "pankaj",
        "paroccepoytamarac@gmail.com",
        "paulseanseo91@gmail.com",
        "pawankundu99@gmail.com",
        "petersmith2331@gmail.com",
        "pintu",
        "pogogames483@gmail.com",
        "poonamkamalpatel@gmail.com",
        "porterquines@gmail.com",
        "PoweltpowWilliamm@gmail.com",
        "pranay4job@gmail.com",
        "pratik.vipra@gmail.com",
        "premk258@gmail.com",
        "printerhelplinenumber@gmail.com",
        "priturpocdickr@gmail.com",
        "quickbook",
        "r.onysokha@gmail.com",
        "rahul4cool2003@gmail.com",
        "rahuldheere@gmail.com",
        "rajdsky7@gmail.com",
        "randyortam68@gmail.com",
        "ravirknayak@gmail.com",
        "rawatsonam",
        "rehel55rk@gmail.com",
        "righttechnical",
        "rikybhel23@gmail.com",
        "Rodriquesnuv728@gmail.com",
        "rohitsharma7294@outlook.com",
        "rohitsona121090@gmail.com",
        "sajankaur5@gmail.com",
        "sandy@voip4callcenters.com",
        "sandysharmja121@gmail.com",
        "sellikepovscotta@gmail.com",
        "seosupport",
        "seoxpertchandan@gmail.com",
        "service.thepc@yandex.com",
        "shilpikispotta2508@gmail.com",
        "shobha.kachhap23@gmail.com",
        "simon.ken7@gmail.com",
        "singhmukul",
        "skprajapaty@gmail.com",
        "sman33935@gmail.com",
        "smartsolution3000@gmail.com",
        "smithlora912@gmail.com",
        "smithmartin919@gmail.com",
        "Sneharajput931@gmail.com",
        "snjbisth8@gmail.com",
        "Sorianonvy291@gmail.com",
        "spyindia",
        "spyvikash",
        "stephanflorian1@gmail.com",
        "sty.besto",
        "stybesto13",
        "sundeepsa123@gmail.com",
        "sunflowerrosy@outlook.com",
        "sunilkundujat@gmail.com",
        "sunjara10@gmail.com",
        "Sweenypar210@gmail.com",
        "telemarket3004",
        "thjyt@yandex.com",
        "tinku8384@gmail.com",
        "tomcopper6@gmail.com",
        "toren55rk@gmail.com",
        "UseenpofChristopherc@gmail.com",
        "vcb@gmail.com",
        "viz.michel@gmail.com",
        "vr4vikasrastogi@gmail.com",
        "vvicky4001@gmail.com",
        "watpad",
        "webdevelopera@gmail.com",
        "webtracker",
        "webtrackker",
        "win.tech",
        "wittepepobjustina@gmail.com",
        "yadavqs@gmail.com",
        "yashraj_one",
        "YoultaspocDonald@gmail.com",
        "ytdeqwduqwy@yandex.com",
        "zebakhan.ssit@gmail.com",
        "zozojams11@gmail.com"
    );

    public static final List<String> IP_BLACKLIST = Arrays.asList(
        "1.186.172.",
        "1.187.114.172",
        "1.187.118.175",
        "1.187.123.123",
        "1.187.126.76",
        "1.187.162.39",
        "1.22.131.140",
        "1.22.164.227",
        "1.22.38.186",
        "1.22.39.244",
        "1.23.110.86",
        "1.39.101.93",
        "1.39.32.",
        "1.39.33.",
        "1.39.34.",
        "1.39.35.",
        "1.39.40.26",
        "1.39.40.30",
        "1.39.50.144",
        "1.39.51.63",
        "101.212.67.25",
        "101.212.69.213",
        "101.212.71.120",
        "101.222.175.13",
        "101.56.2.232",
        "101.57.198.190",
        "101.59.76.223",
        "101.60.",
        "101.63.200.188",
        "103.10.197.194",
        "103.18.72.91",
        "103.19.153.130",
        "103.192.64.",
        "103.192.65.",
        "103.192.66.163",
        "103.204.168.18",
        "103.22.172.142",
        "103.226.202.171",
        "103.226.202.211",
        "103.233.116.124",
        "103.233.118.222",
        "103.245.118.",
        "103.254.154.229",
        "103.43.33.101",
        "103.44.18.221",
        "103.47.168.57",
        "103.47.169.7",
        "103.49.49.49",
        "103.50.151.68",
        "103.55.",
        "104.156.228.84", // http://www.ipvoid.com/scan/104.156.228.84
        "104.200.154.4", // http://www.ipvoid.com/scan/104.200.154.4
        "104.236.123.17", // persistent spammer
        "106.201.144.243",
        "106.201.196.70",
        "106.204.124.188",
        "106.204.142.176",
        "106.204.236.224",
        "106.204.246.196",
        "106.204.50.214",
        "106.205.188.247",
        "106.215.176.34",
        "106.67.102.143",
        "106.67.113.167",
        "106.67.118.250",
        "106.67.28.163",
        "106.67.28.22",
        "106.67.46.209",
        "106.67.76.237",
        "106.67.89.196",
        "106.76.167.41",
        "109.163.234.8", // http://www.ipvoid.com/scan/109.163.234.8
        "110.172.140.98",
        "110.227.181.55",
        "110.227.183.246",
        "110.227.183.36",
        "111.93.63.62",
        "112.110.2.245",
        "112.196.147.",
        "112.196.160.122",
        "112.196.160.61",
        "112.196.170.150",
        "112.196.170.8",
        "114.143.173.139",
        "115.111.223.43",
        "115.112.159.250",
        "115.114.191.92",
        "115.115.131.166",
        "115.160.250.34",
        "115.184.",
        "116.202.36.",
        "116.203.",
        "117.193.130.11",
        "117.198.",
        "117.201.159.73",
        "117.201.166.21",
        "117.242.5.201",
        "119.81.230.137",
        "119.81.249.132", // http://www.ipvoid.com/scan/119.81.249.132
        "119.81.253.243", // http://www.ipvoid.com/scan/119.81.253.243
        "119.82.95.142",
        "120.57.17.65",
        "120.57.86.248",
        "120.59.205.205",
        "121.242.40.15",
        "121.242.77.200",
        "121.244.181.162",
        "121.244.95.1",
        "121.245.126.7",
        "121.245.137.28",
        "122.162.88.67",
        "122.167.104.9",
        "122.169.130.19",
        "122.173.",
        "122.175.221.219",
        "122.176.18.41",
        "122.176.78.14",
        "122.177.",
        "122.180.",
        "123.136.209.119",
        "123.239.77.189",
        "123.254.107.229",
        "124.41.241.203",
        "125.16.2.102",
        "125.63.104.53",
        "125.63.107.204",
        "125.63.73.249",
        "125.63.96.184",
        "125.63.99.102",
        "128.199.242.223", // persistent spammer
        "136.185.192.239",
        "138.128.180.",
        "14.141.1.58",
        "14.141.148.206",
        "14.141.148.222",
        "14.141.51.5",
        "14.96.",
        "14.98.",
        "155.254.246.",
        "169.57.0.235", // http://www.ipvoid.com/scan/169.57.0.235
        "171.48.32.3",
        "171.48.38.188",
        "171.50.131.221",
        "171.50.146.100",
        "171.50.42.142",
        "172.98.67.25", // http://www.ipvoid.com/scan/172.98.67.25
        "172.98.67.71", // http://www.ipvoid.com/scan/172.98.67.71
        "176.53.21.213", // http://www.ipvoid.com/scan/176.53.21.213
        "176.67.85.1", // http://www.ipvoid.com/scan/176.67.85.1
        "176.67.86.12", // laptoprepair gmail guy
        "176.67.86.36", // Persistent spammer
        "177.154.139.203", // http://www.ipvoid.com/scan/177.154.139.203
        "180.151.",
        "180.254.96.177", // http://www.ipvoid.com/scan/180.254.96.177
        "182.156.72.162",
        "182.156.89.34",
        "182.64.",
        "182.68.",
        "182.69.",
        "182.71.71.102",
        "182.73.117.142",
        "182.73.182.170",
        "182.74.88.42",
        "182.75.144.58",
        "182.75.176.202",
        "182.77.14.159",
        "182.77.8.92",
        "183.82.199.55",
        "185.22.232.91", // Persistent spammer
        "196.207.106.219",
        "196.207.107.56",
        "198.8.80.172", // http://www.ipvoid.com/scan/198.8.80.172
        "202.159.213.10",
        "202.53.94.4",
        "202.91.134.66",
        "202.91.134.67",
        "202.91.76.164",
        "202.91.76.82",
        "203.110.83.66",
        "203.122.16.168",
        "203.122.41.130",
        "203.122.7.236",
        "203.99.192.210",
        "212.83.165.204", // http://www.ipvoid.com/scan/212.83.165.204/
        "216.185.103.139", // http://www.ipvoid.com/scan/216.185.103.139
        "223.176.141.173",
        "223.176.142.230",
        "223.176.143.154",
        "223.176.152.27",
        "223.176.156.193",
        "223.176.159.235",
        "223.176.165.11",
        "223.176.176.254",
        "223.176.178.24",
        "223.176.189.144",
        "223.180.245.176",
        "223.183.67.247",
        "223.225.42.57",
        "27.56.47.65",
        "27.60.131.203",
        "27.7.210.21",
        "27.7.213.175",
        "27.7.231.16",
        "27.255.252.28",
        "38.132.106.132", // http://www.ipvoid.com/scan/38.132.106.132
        "38.95.108.245", // http://www.ipvoid.com/scan/38.95.108.245
        "38.95.109.37", // http://www.ipvoid.com/scan/38.95.109.37
        "38.95.109.67", // http://www.ipvoid.com/scan/38.95.109.67
        "41.215.241.114", // Persistent spammer IP
        "43.230.198.",
        "43.239.204.41",
        "43.239.68.7",
        "43.239.68.202",
        "43.245.149.107",
        "43.245.151.",
        "43.245.211.71",
        "43.251.84.",
        "43.252.24.155",
        "43.252.27.52",
        "43.252.29.202",
        "43.252.30.93",
        "43.252.31.138",
        "43.252.32.181",
        "43.252.33.70",
        "43.252.35.80",
        "45.114.63.184",
        "45.115.",
        "45.120.162.172",
        "45.120.56.65",
        "45.121.188.46",
        "45.121.189.236",
        "45.121.191.78",
        "45.122.120.178",
        "45.122.123.47",
        "45.127.40.20",
        "45.127.40.231",
        "45.127.42.63",
        "45.127.43.154",
        "45.42.243.83",
        "45.55.3.174",
        "45.56.154.150",
        "46.101.245.193", // Persistent spammers
        "46.165.208.207", // http://www.ipvoid.com/scan/46.165.208.207
        "46.21.149.10", // jamesthomas (manasfirdose@gmail.com)
        "49.15.149.23",
        "49.15.158.7",
        "49.156.150.242",
        "49.204.252.214",
        "49.244.214.39",
        "5.62.5.71", // http://www.ipvoid.com/scan/5.62.5.71
        "59.180.132.51",
        "59.180.25.215",
        "59.180.27.191",
        "61.0.85.206",
        "61.12.72.244",
        "61.12.72.246",
        "62.210.139.80", // proxy? twice an Indian spammer jumped to this IP
        "69.65.43.205", // http://www.ipvoid.com/scan/69.65.43.205
        "81.218.235.170", // http://www.ipvoid.com/scan/81.218.235.170
        "91.121.219.236", // http://www.ipvoid.com/scan/91.121.219.236
        "91.205.175.34", // http://www.ipvoid.com/scan/91.205.175.34
        "92.114.94.106", // http://www.ipvoid.com/scan/92.114.94.106/
        "93.114.44.187", // http://www.ipvoid.com/scan/93.114.44.187
        "93.115.92.169" // http://www.ipvoid.com/scan/93.115.92.169
    );

    public static final List<String> USE_BLACKLIST = Arrays.asList(
        "About Jenkins CI",
        "ad",
        "add a page",
        "add page",
        "admin",
        "admins",
        "advertisement",
        "advertising",
        "article",
        "articles",
        "asdf",
        "blog submit",
        "blog user",
        "blog writing",
        "blog",
        "bloging",
        "blogs",
        "bug tracker",
        "business",
        "businessman",
        "bussiness",
        "captcha",
        "capturing",
        "company",
        "content marketing",
        "content",
        "creating",
        "discussion",
        "donation",
        "drama",
        "edit page",
        "edit",
        "edu",
        "food",
        "For Bloging",
        "for jenkins information",
        "for news",
        "for using wiki and jira",
        "for wiki and jira use",
        "forum post",
        "Forum posting",
        "forum",
        "Fun Wiki",
        "fun",
        "funtime",
        "game",
        "get informaion",
        "get more information",
        "god",
        "good",
        "google",
        "group discussion",
        "gui",
        "help support",
        "help",
        "helpline and support",
        "helpline",
        "https://jenkins-ci.org",
        "https://jenkins-ci.org/account/signup",
        "i love this site",
        "info",
        "information",
        "interest",
        "internet",
        "JDBC",
        "jenkins for",
        "jenkins",
        "jenkins-ci.org",
        "jira",
        "join group",
        "just",
        "keyword promotion",
        "knowledge sharing",
        "knowledge",
        "learn",
        "linux tips",
        "looking for voip solutions",
        "love",
        "marketing",
        "material",
        "meet jenkins",
        "networking",
        "news update",
        "news",
        "no",
        "none",
        "nothing",
        "ODBC",
        "office use",
        "office",
        "on upload",
        "other",
        "page",
        "pcsupport",
        "permotions",
        "post profile",
        "post something",
        "post submission",
        "post",
        "posting for facebook",
        "posting for feedback",
        "posting for gmail",
        "posting for information",
        "posting",
        "pratice",
        "printers classified",
        "printers",
        "problem solved",
        "profile",
        "Promote our website",
        "promotion",
        "Publicity",
        "publish",
        "question",
        "Quora",
        "read a forum",
        "read and write",
        "read",
        "reading",
        "reCAPTCHA",
        "research",
        "robot",
        "search",
        "searching",
        "see",
        "seo",
        "service",
        "share info",
        "share my knowledge",
        "share post",
        "sharing",
        "social",
        "software information",
        "solve problem",
        "spam",
        "spread information",
        "student",
        "studies",
        "study of forum communities",
        "study",
        "submit page",
        "support",
        "surfing",
        "teacher",
        "tech support",
        "technical help support",
        "technical help",
        "technical support",
        "tutorial",
        "tutorials",
        "use",
        "useful",
        "user blog",
        "want to study",
        "web page",
        "website",
        "what",
        "wiki and jira",
        "wiki page",
        "wiki pages",
        "wiki submission",
        "wiki",
        "yes",
        "zira"
    );

    public static final List<String> USERID_BLACKLIST = Arrays.asList(
        "intuitphonenumber",
        "jamesbond",
        "larrysilva",
        "macden",
        "quickbook",
        "smartjane",
        "watpad"
    );

    public static final String SPAM_MESSAGE = "Due to the spam problem, we will need additional verification for your sign-up request. " +
            "More details are found on <a href='https://wiki.jenkins-ci.org/display/JENKINS/User+Account+on+Jenkins'>our wiki</a> on how to get your account created.";

    // Somewhat cryptic name for cookie, so prying eyes don't know its use.
    public static final String ALREADY_SIGNED_UP = "JENKINSACCOUNT";
}
