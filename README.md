The Feed: This application is a java program that allows us to send an email with a url and have it automatically
posted to an RSS feed. 

Sorry this program is no longer actually running.  Below is how it used to work.  Even though I should have known
better, I hard coded the gmail password into the .java file.  Now a year+ later, I had to change my password 
and no longer have the environment to easily re-complie the code. 

What you do here is subscribe to this RSS feed with your favorite reader:  http://bobopearl.zapto.org/rss.xml
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

