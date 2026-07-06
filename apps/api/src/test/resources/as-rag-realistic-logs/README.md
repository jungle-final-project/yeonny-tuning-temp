# AS RAG Realistic Synthetic Logs

These JSONL files are synthetic fixtures, not copied customer logs.

They are based on Windows event families that support teams commonly inspect, plus BuildGraph Agent health/sensor rows defined by the PC Agent RawLog contract:

- `Microsoft-Windows-Kernel-Power` Event ID 41 style unexpected restart
- `EventLog` Event ID 6008 style previous unexpected shutdown
- `Microsoft-Windows-WHEA-Logger` hardware error style events
- `BugCheck` Event ID 1001 style reboot-from-bugcheck event
- `Disk` Event ID 7 style bad block event
- `Ntfs` Event ID 55 style file system corruption event
- `Display` Event ID 4101 style display driver recovery event
- `Application Error` Event ID 1000 style faulting application event
- `.NET Runtime` Event ID 1026 style unhandled exception event
- `Microsoft-Windows-DNS-Client` Event ID 1014 style name resolution timeout
- `Service Control Manager` Event ID 7031 style service terminated unexpectedly
- `Microsoft-Windows-Resource-Exhaustion-Detector` Event ID 2004 style low virtual memory

The envelope is the project RawLog JSONL format, so each line includes `schemaVersion`, `collectedAt`, `agentId`, `sequence`, `kind`, `payload`, and `privacyFlags`.
