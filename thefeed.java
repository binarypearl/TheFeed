/*
	'The Feed':  What you do here is subscribe to this RSS feed with your favorite reader:  http://bobopearl.zapto.org/rss.xml
	Now anyone can post a new link to this RSS feed by simply adding a URL anywhere into the body of an email 
	to bobopearlrssfeeds@gmail.com.

	The java program below is what reads new emails from this email account and parses out the URL's.  
	It then writes the link to an RSS xml file that apache helps serve up.

	How it runs:
	thefeed.jar is ran through a cronjob ever 30 minutes through `java -jar /path/to/thefeed.jar`;
	I went back and forth on whether to run this as a daemon or run it through cron.  I decided to use cron
	in this case mainly because it had (initially) a high propensity to crash.  If we ran this through
	a daemon then there was no chance of it running again until we fixed it.  Through cron, it if 
	crashes, we have a chance that it will get into another situation and still process some emails.

	Notes:
	1.  This is version 2.0.  Version 1.0 was written in perl.  The perl version had an issue where some links
	    were not getting parsed properly, creating partial and malformed links.  Turns out this java version
	    had the same problem as well.  What was happening is that in certain situations a hidden newline character
	    was getting inserted into the url.  Fortunately at one point I was able to see both the same URL in a situation
	    where it worked fine, and another situation where it failed.  Visually I copied and pasted the urls
	    into a text editor, and they looked absolutely identical.  So I ran both urls through this java 
	    program, but printed each character in unicode.  On the bad url there was an extra \u000d that
	    was added.  So now I look for that particular unicode character and replace it with an empty 
	    string.  And now we don't get broken links from this former bug anymore.

	2.  A new feature in 2.0 that came from the hidden newline bug mentioned above, is that this program
	    will do a check on the url parsed out by making an http connection and examining the 
	    return code.  If we got a bad return code, we email the admin (in this case me
            at binarypearl@gmail.com) with the bad link.  I had gmail filters setup to assign
	    a label so that these emails stood out.  This allows me to catch bad links, so I can investigate
	    them to see what happened.  Fortunately now it doesn't happen often, but it's good 
	    to have this feature so I can act accordingly.

	3.  The email system used here is way to low-level.  It uses the JavaMail API, which is technically
	    a part of J2EE, and not J2SE.  So there is enormous amount of complexity added here just to
	    be able to send and retrieve emails.  Also the password for the email accounts is hard coded 
	    in this file (but replaced with a generic string for the purposes of github).  

	4.  The log file is unnecessarily written to.  Each pass writes some header lines, even if there
	    is nothing to print.  This is just a bug that needs to be worked out.
*/

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.FileWriter;
import java.io.BufferedWriter;

import java.net.HttpURLConnection;
import java.net.URL;

import java.text.SimpleDateFormat;

import java.util.Properties; 
import java.util.Date;

import java.util.regex.Pattern;
import java.util.regex.Matcher;

import javax.mail.Flags;
import javax.mail.MessagingException;
import javax.mail.NoSuchProviderException;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.Multipart;
import javax.mail.BodyPart;
import javax.mail.Address;
import javax.mail.Transport;
import javax.mail.search.FlagTerm;

import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

/* 
  From a java structure viewpoint, this program is rather simple.  It only contains one
  class "thefeed".
*/

public class thefeed {
	// hard coded directories/files isn't the best here, but it's fine for now.
	static String rss_xml_file_path = "/var/www/html/rss.xml";
	static String rss_xml_file_temp_path = "/var/www/html/rss.temp.xml";
	
	static String log_file_path = "/var/www/html/thefeed.log";
	
	private static final int MAX_TRIES = 10;
	static int current_tries = 0;

	// I think this variable isn't being used...queue for deletion.	
	static String sender = "dummy sender";

	// Part of how we do regular expressions in java.  This creates our pattern of what we want to search for in a string.	
	static Pattern pattern = Pattern.compile ("http", Pattern.CASE_INSENSITIVE);

	// A simple main().  All we do is call get_emails().  Most of the rest of the action happens there.	
	public static void main (String[] argv) {
		try {
			get_emails();
		}
		
		catch (IOException e) {
			System.out.println ("If we got here, we probably just had a timeout getting an email.  No big deal, it's handled elsewhere.");
		}
	}

	// get_emails() does almost the rest of the processing.  The only thing it doesn't do is write the xml file.  There is another routine for that.

	public static void get_emails() throws IOException {	
		// 2nd argument to FileWriter is whether to append or not.
		FileWriter log_file_handle = new FileWriter (log_file_path, true);
		BufferedWriter log_file_buffer = new BufferedWriter (log_file_handle);
		
		log_file_buffer.write ("--------------------------------------------------------------------------------\n");

		// Well, first lets hard code some values:
		// Connecting to gmail is not trivial.  It took some searching to figure out what values it wanted. 
		String protocol = "imaps";
		String username = "bobopearlrssfeeds@gmail.com";
		String password = "email_password_goes_here";
		String host = "imap.gmail.com";
		
		URL site_to_test = null;
		HttpURLConnection connection = null;
		
		int response_code = 0;
		
		String url_chomped_potential = "";
		String url_chomped_full = "";
		
		// JavaMail wants a Session object at it's core:
		Session session = Session.getDefaultInstance (System.getProperties(), null);
		session.setDebug(false);
		
		Store store = null;
		
		// JavaMail now wants a Store object:

		try {
			//String SSL_FACTORY = "javax.net.ssl.SSLSocketFactory";
			
			//Properties pop3Props = new Properties();
			Properties imapProps = System.getProperties();

			imapProps.setProperty ("mail.store.protocol", protocol);
			imapProps.setProperty ("mail.imaps.host", host);
			imapProps.setProperty ("mail.imaps.port", "993");
			imapProps.setProperty ("mail.imaps.connectiontimeout", "1000");
			imapProps.setProperty ("mail.imaps.timeo0ut", "1000");

			session = Session.getDefaultInstance (imapProps, null);
			
			store = session.getStore ("imaps");
			store.connect(host, username, password);
			
			// Let's get the default/root mail directory now:
			Folder root_folder = store.getFolder ("INBOX");
		
			// If I recall correctly, we open this READ_WRITE to we can mark emails as read.  Would
			// have to confirm though if that's really necessary or not.	
			root_folder.open (Folder.READ_WRITE);
			
			FlagTerm ft = new FlagTerm (new Flags (Flags.Flag.SEEN), false);
			Message[] msgs = root_folder.search (ft);
		
			// For each email message...	
			for (int i = 0; i < msgs.length; i++) {
				Message email = msgs[i];
				
				// Lets get the From address
				Address from_address_address = email.getFrom()[0];
				String from_address_string;
			
				// Recalling, email can come in as one of several different formats.
				// Basically it can come in as a straight up string, or a more complicated
				// MIME object.  So we use the instanceof call here to help determine
				// which format the email is in.  If you tried reading an email and it
				// was the wrong type, the_feed would crash.  We use this tactic serveral times
				// below.
	
				if (from_address_address instanceof InternetAddress) {
					from_address_string = ((InternetAddress)from_address_address).getAddress();
				}
				
				else {
					from_address_string = from_address_address.toString();
				}
	
				// A little note about the debug symbols.  
				// "D[0-9][0-9]: stuff -> D stands for debug.  The numbers are there just to help trace where the message is coming from.
				// "E[0-9][0-9]: stuff -> E stands for error.  The numbers are there just to help trace where the message is coming from.
				// "W[0-9][0-9]: stuff -> E stands for warning.  The numbers are there just to help trace where the message is coming from.
	
				try {
					String body_string = "";
					BodyPart bodyPart;
					String[] body_string_array = {};
					
						Object email_content= email.getContent();
			
						if (email_content instanceof String) {
							log_file_buffer.write ("D03: email content is of type String.\n");
							body_string = (String) email_content;
							body_string_array = ((String) email_content).split ("\n");
						}
						
						else if (email_content instanceof Multipart) {	
							log_file_buffer.write ("D05: email content is of type Multipart.\n");
							
							bodyPart = ((Multipart) email_content).getBodyPart(0);
							body_string = (String) bodyPart.getContent();
							body_string_array = body_string.split("\n");	
						}
						
						else {
							// If we got here, that means we have some other format type that we are not famimliar with.
							// Hopefully we never get here but never know what may come up some day.  
							// At this point the email would not be parsable until we make the appropriate code changes.
							log_file_buffer.write ("D07:  email content not of String or Multipart.  Skipping...\n");
							continue;
						}
						
						for (int j = 0; j < body_string_array.length; j++) {
							// This is where we handle the rogue newlines that were sometimes getting inserted into
							// the URL's.  We unconditionally search and replace all occurrences of \u000d with an
							// empty string.  Looks like we are also replacing & with &amp.  This makese sense
							// but what about other character translations like this?
							url_chomped_potential = body_string_array[j].replaceAll("\\u000d", "");
							url_chomped_potential = url_chomped_potential.replaceAll("&", "&amp;");
							
							url_chomped_full += url_chomped_potential;							
							
							log_file_buffer.write ("D10: url_chomped_potential: " + url_chomped_potential + "\n");
							log_file_buffer.write ("D20: url_chomped_full: " + url_chomped_full + "\n");
							
							Matcher match_object = pattern.matcher(url_chomped_full);		
																					
							if (match_object.find()) {
								url_chomped_full = url_chomped_full.replaceAll (".*http", "http");
								
								
								log_file_buffer.write ("D30: http found\n");
								
								// OK, we found http, but is the link really valid?
								// So, let's try to connect.  If we succeed, continue as normal.
								// If we fail, lets append the next line to the previous line and try again.
							
								// What I'm doing here is doing a GET on the url.  Looks like we are setting
								// a 1 minute timeout..60,000 milliseconds?  We are whitelisting the return codes.
								//200, 301, and 403 are considered good.  Everything else will fail.  I don't really
								// like this.  It hasn't been a problem for the most part, but thinking it might have
								// been better to blacklist the bad returns codes instead.
	
								try {
									site_to_test = new URL (url_chomped_full);
									
									connection = (HttpURLConnection)site_to_test.openConnection();
									connection.setRequestMethod("GET");
									connection.setReadTimeout(60000);
									
									connection.connect();
									
									response_code = connection.getResponseCode();
									
									if (response_code == 200 || response_code == 301  || response_code == 403) {
										if (response_code == 200) {
											log_file_buffer.write ("D40: 200 response code here.\n");	
										}
										
										else if (response_code == 301) {
											log_file_buffer.write ("W01: 301 response code; assuming ok...\n");
										}
										
										else if (response_code == 403) {
											log_file_buffer.write ("W02: 403 response code; assuming ok...\n");
										}
										
										System.out.println ("Writing url: " + url_chomped_full);
										
										// Lets get the current date for the <pubDate> tag in the exact format rss wants:
										Date right_now = new Date();
					
										SimpleDateFormat formatter = new SimpleDateFormat ("EEE, dd MMMMM yyyy HH:mm:ss z");
											
										String date = formatter.format (right_now);
											
										modify_rss_xml (url_chomped_full, date, from_address_string);

										url_chomped_potential = "";
										url_chomped_full = "";
										
										// Bad hack to fool the array that we are done with the email.
										j = body_string_array.length + 10;
										
									}
									
									else {
										// This is what we do if we got a bad return code:

										log_file_buffer.write ("D45: response code: " + response_code + "\n");
										// If we hit here, we have a failed url
										if (j == (body_string_array.length - 1)) {
											System.out.println ("D50: BAD link (" + response_code + "): " + url_chomped_full  + "\n");
											log_file_buffer.write ("D50: BAD link (" + response_code + "): " + url_chomped_full  + "\n");
											
											Properties send_properties = new Properties();
											
											send_properties.setProperty ("mail.smtp.starttls.enable", "true");
											send_properties.setProperty ("mail.smtp.host", "smtp.gmail.com");
											send_properties.setProperty ("mail.smtp.user", "bobopearlrssfeeds");
											send_properties.setProperty ("mail.smtp.password", "email_password_goes_here");
											send_properties.setProperty ("mail.smtp.auth", "true");
											send_properties.setProperty ("mail.smtp.port", "587");
											
											Session send_session = Session.getInstance(send_properties, null);
											
											try {
												Message exception_message = new MimeMessage (send_session);
												exception_message.setFrom (new InternetAddress ("bobopearlrssfeeds@gmail.com"));
												exception_message.setRecipients(Message.RecipientType.TO, InternetAddress.parse("binarypearl@gmail.com"));
												exception_message.setSubject ("BAD LINK DETECTED");
												
												exception_message.setText ("Bad URL: " + url_chomped_full + "\n" 
														+ "Subject: ***" + email.getSubject() + "***\n" + "Response code: ***" + response_code + "***");
																								
												Transport transport = send_session.getTransport("smtp");
												transport.connect ("smtp.gmail.com", "bobopearlrssfeeds", "email_password_goes_here");
												transport.sendMessage  (exception_message, exception_message.getAllRecipients());
												transport.close();
												
												url_chomped_potential = "";
												url_chomped_full = "";
												
												// Bad hack to fool the array that we are done with the email.
												j = body_string_array.length + 10;

											}
											
											catch (MessagingException e) {
												System.err.println ("E03:  Probably had trouble sending email I guess: " + e);
												log_file_buffer.write ("E03:  Probably had trouble sending email I guess: ");
											}
										}
									}
									
								}
								
								catch (IOException e) {
									//throw new RuntimeException (e);
									System.out.println ("E04: (Timeout): " + url_chomped_full);
									log_file_buffer.write("E04: (Timeout): " + url_chomped_full);
									
									Properties send_properties_2 = new Properties();
									
									send_properties_2.setProperty ("mail.smtp.starttls.enable", "true");
									send_properties_2.setProperty ("mail.smtp.host", "smtp.gmail.com");
									send_properties_2.setProperty ("mail.smtp.user", "bobopearlrssfeeds");
									send_properties_2.setProperty ("mail.smtp.password", "email_password_goes_here");
									send_properties_2.setProperty ("mail.smtp.auth", "true");
									send_properties_2.setProperty ("mail.smtp.port", "587");
									
									Session send_session_2 = Session.getInstance(send_properties_2, null);
									
									Message timeout_message = new MimeMessage (send_session_2);
									timeout_message.setFrom (new InternetAddress ("bobopearlrssfeeds@gmail.com"));
									timeout_message.setRecipients(Message.RecipientType.TO, InternetAddress.parse ("binarypearl@gmail.com"));
									timeout_message.setSubject ("TIMEOUT LINK DETECTED");
									
									timeout_message.setText ("Manually check URL: " + url_chomped_full + "\n" 
											+ "Subject: ***" + email.getSubject() + "***");
									
									Transport transport = send_session_2.getTransport("smtp");
									transport.connect ("smtp.gmail.com", "bobopearlrssfeeds", "email_password_goes_here");
									transport.sendMessage  (timeout_message, timeout_message.getAllRecipients());
									transport.close();

									// Lets get the current date for the <pubDate> tag in the exact format rss wants:
									Date right_now = new Date();
				
									SimpleDateFormat formatter = new SimpleDateFormat ("EEE, dd MMMMM yyyy HH:mm:ss z");
										
									String date = formatter.format (right_now);
									
									modify_rss_xml (url_chomped_full, date, from_address_string);
									
									url_chomped_potential = "";
									url_chomped_full = "";
									
									// Bad hack to fool the array that we are done with the email.
									j = body_string_array.length + 10;
								}
						}
							
						else {
							log_file_buffer.write ("D35: http NOT found\n");
						}

					}  // End for loop for a particular email						
				} // End try

				catch (IOException e) {
					System.err.println ("Error with getContent(): " + e);
					e.printStackTrace();
				}

			}  // End big for
			
			// The argument here expunges all deleted messages if set to true.  Doesn't really matter...but well go false for now 
			root_folder.close(false);
		} 
		
		catch (NoSuchProviderException e) {
				System.err.println ("NoSuchProviderException: " + e);
				e.printStackTrace();
		} 
		
		catch (MessagingException e) {
			// Basically I timeout things my number of tries, not by a fixed amount of time.  Each try is given a 5 second delay,
			// but we timeout after 10 tries.
	
			if (current_tries >= MAX_TRIES) {
				System.out.println ("E02:  Ok we had enough, we tried connecting 10 times and failed.  Exiting.");
				log_file_buffer.write ("E02:  Ok we had enough, we tried connecting 10 times and failed.  Exiting.\n");
				
				// This is bad, but it's the only way right now I can get the files to write.
				log_file_buffer.write ("--------------------------------------------------------------------------------\n\n");
				
				log_file_buffer.close();
				log_file_handle.close();
				
				System.exit(1);
			}
			
			else {
				System.out.println ("E01:  MessagingException here:  Something didn't work, lets just try again.");
				log_file_buffer.write ("E01:  MessagingException here:  Something didn't work, lets just try again.\n");
				
				try {
					Thread.sleep (5000);
				}
				
				catch (InterruptedException e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				}
				
				current_tries++;
				
				// This is bad, but it's the only way right now I can get the files to write.
				log_file_buffer.write ("--------------------------------------------------------------------------------\n\n");
				
				log_file_buffer.close();
				log_file_handle.close();
				
				get_emails();
				//System.err.println ("MessagingException: " + e);
				//e.printStackTrace();
			}
		}	
		
		log_file_buffer.write ("--------------------------------------------------------------------------------\n\n");
		
		log_file_buffer.close();
		log_file_handle.close();
	}


	// modify_rss_xml() is the routine to write out our new xml file.
	// The way this works is that we always add the new entry in question as the first entry.  So there is some header stuff first,
	// so we look for "<language>en-us</language>".  This is our key that we can start writing a new xml block for our link.
	// 
	// As far as how we read and write, we open the existing file for reading.  We then write each record to a temporary file.  If the
	// record being read doesn't need to be added/modified, we just write as is.  Otherwise we write our new records instead.  Once we are
	// done with all of the records from the original file, we then close the files, then copy our temporary xml file over the original 
	// file.
		
	public static void modify_rss_xml(String new_link, String new_pub_date, String new_sender) throws FileNotFoundException, IOException {
		String new_link_chomped = new_link.replace("\r", "");
	
		BufferedReader rss_xml_read_file_handle = new BufferedReader (new FileReader (rss_xml_file_path));
		FileWriter rss_xml_write_file_handle = new FileWriter (rss_xml_file_temp_path, false);
		BufferedWriter rss_xml_write_buffer = new BufferedWriter (rss_xml_write_file_handle);
		
		Pattern rss_block_marker_pattern = Pattern.compile("<language>en-us</language>");
		
		String record;
		
		while ((record = rss_xml_read_file_handle.readLine()) != null) {
			Matcher rss_block_marker_matcher = rss_block_marker_pattern.matcher(record);
			
			if (rss_block_marker_matcher.find()) {
				rss_xml_write_buffer.write ("\t\t<language>en-us</language>\n");
				rss_xml_write_buffer.write ("\t\t<item>\n");
				rss_xml_write_buffer.write ("\t\t\t<title>" + new_link_chomped + "</title>\n");				
				rss_xml_write_buffer.write ("\t\t\t<link>" + new_link_chomped + "</link>\n");
				rss_xml_write_buffer.write ("\t\t\t<guid>" + new_link_chomped + "</guid>\n");
				rss_xml_write_buffer.write ("\t\t\t<pubDate>" + new_pub_date + "</pubDate>\n");
				rss_xml_write_buffer.write ("\t\t\t<description>Posted by: " + new_sender + "</description>\n");
				rss_xml_write_buffer.write ("\t\t</item>\n");
				rss_xml_write_buffer.write ("\n");
			}
			
			else {
				rss_xml_write_buffer.write(record + "\n");
			}
		}
		
		rss_xml_write_buffer.close();
		rss_xml_read_file_handle.close();
		
		// do a system file copy of our temp xml file to our real xml file:
		Process p = Runtime.getRuntime().exec("cp " + rss_xml_file_temp_path + " " + rss_xml_file_path);
		try {
			p.waitFor();
		} 
		
		catch (InterruptedException e) {
			System.err.println ("oops with cp command: " + e);
			e.printStackTrace();
		}
	}
}

