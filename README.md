# SoarLog
A pilot flight logbook for gliding.

## Why:
I have been using a Memento database to record my flights. It works very well but it uses a cloud service to sync data and the export option is adequate, but not great. The free version has limitations on filtering that were a minor nuisance. I don't see the need to pay for something just to keep a simple record of flights.
I enter my flights into Memento when I finish flying, then transfer to my paper logbook sometime later. 
I wouldn't mind dropping the paper logbook if my digital one is compliant with local rules and sufficiently robust.
Yes, this is all about me. But if it's useful to you, all the better.

## What it does:
* Provides a quick and easy place to log flights.
* Your data is yours to do with as you like. It is stored internally in a SQLite database but full backups can be exported to text based JSON and all log data can be exported to CSV for easy transfer to spreadsheets or other systems. You will not lose access to your data even if the app ceases to exist.
* Provides dropdown lists for Gliders, Airfields and Clubs, with configurable defaults.
* Can be configured to record just flight time or launch, landing & flight time. Useful for compliance with some jurisdiction rules.
* Can be configured to save Landing place. Useful for compliance with some jurisdiction rules.
* Automatically selects the launch airfield and optionally the landing airfield (relies on airfield location being set).
* Select IGC file to automatically create a log record.
* Send the selected IGC file to WeGlide.
* Select an IGC file from an LXNav bluetooth enabled device (Nano, S100 etc)
* Time calculator to make filling in aircraft logbook easier. Provides last known AC logbook total + current day total. Last known total can be updated from the paper logbook to give the correct running total. For single user private gliders this will probably stay in sync with the glider logbook. For club and syndicate gliders you simply enter the last figure from the logbook and the app provides the new running total.

## What it doesn't do:
* Store, display or analyse IGC files. IGC files are parsed for flight launch and landing data then dropped or passed on to WeGlide.
* In-flight logging. This is a logbook replacement, not a logger.
* Send your data anywhere off your device without you telling it to. No central database, no cloud storage, no third-party processing. WeGlide is the exception and only if you specifically tell it to.

