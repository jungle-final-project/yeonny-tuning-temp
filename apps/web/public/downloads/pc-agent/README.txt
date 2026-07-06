PCAgent download

This local demo build does not ship a signed installer or Windows Service yet.

Web download gives you PCAgent.zip.
Extract the zip first, then double-click PCAgent.exe:

  Creates %LOCALAPPDATA%\BuildGraphAgent\agent-config.json when missing.
  Creates %LOCALAPPDATA%\BuildGraphAgent\logs.
  Copies PCAgent.exe to %LOCALAPPDATA%\BuildGraphAgent\PCAgent.exe.
  Registers a startup command that points to the stable local copy.
  Starts demo metric collection in the background.
  Reads pcagent-activation.json once to register this device.
  Deletes pcagent-activation.json after successful registration.
  Shows a PCAgent tray icon.

The zip contains:

  PCAgent.exe
  pcagent-activation.json
  README.txt

Tray menu:

  Open log viewer
  Open log folder
  Open AS page
  Stop

The log viewer lets you choose a date and 1-hour range.

Config fields:

  apiBaseUrl: Agent API server, for example http://localhost:8080
  webBaseUrl: Web support page base URL, for example http://localhost:5173

When register saves agentToken, the agent attempts to restrict the config file
ACL to the current Windows user, Administrators, and SYSTEM.

For Windows packaging, rebuild agent.exe from the repository:

  cd apps\pc-agent
  build-agent-exe.cmd

The build creates:

  apps\pc-agent\dist\agent.exe      silent tray/viewer executable
  apps\pc-agent\dist\agent-cli.exe  console executable for status/doctor

Run examples:

  Extract PCAgent.zip
  PCAgent.exe
  The extracted folder can be moved or deleted after PCAgent starts successfully.
  agent-cli.exe doctor --config agent-config.json
  agent-cli.exe collect --config agent-config.json --iterations 1
  agent-cli.exe upload --config agent-config.json --no-open

Not included yet:

  Windows Service
  installer
  auto-update
  signed release channel
