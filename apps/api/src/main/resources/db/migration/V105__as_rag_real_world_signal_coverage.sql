UPDATE as_rag_evidence
SET metadata = '{"keywords":["display driver reset","display driver stopped responding","display driver","gpu driver","graphics driver","nvlddmkm","device manager","device not migrated","windows update failure","windows update failed","windows update error","driver install failure","driver failed to load","driver rollback","rollback","wudfrd failed to load","드라이버 오류"],"remoteActions":["DRIVER_ROLLBACK","WINDOWS_UPDATE_CHECK","DEVICE_RESET"],"visitReasons":[]}'::jsonb
WHERE source_id = 'as-rag-remote-driver-os';

UPDATE as_rag_evidence
SET metadata = '{"keywords":["app crash","application crash","faulting application","application error","launcher crash","launcher","runtime missing",".net runtime","sidebyside","side-by-side","vcruntime","msvcp","0xc000007b","installer error","permission denied","cache corruption"],"remoteActions":["APP_REPAIR","RUNTIME_INSTALL","CACHE_CLEANUP"],"visitReasons":[]}'::jsonb
WHERE source_id = 'as-rag-remote-app-launcher';

UPDATE as_rag_evidence
SET metadata = '{"keywords":["free space low","storage low","low disk space","memory pressure","virtual memory","low virtual memory","pagefile","pagefile usage","disk active time","disk queue length","commit charge","commit limit","resource exhaustion"],"remoteActions":["TEMP_FILE_CLEANUP","STARTUP_APP_REVIEW","PAGEFILE_CHECK"],"visitReasons":[]}'::jsonb
WHERE source_id = 'as-rag-remote-storage-memory';

UPDATE as_rag_evidence
SET metadata = '{"keywords":["dns failure","dns timeout","name resolution failed","name resolution timed out","dns client events","gateway unreachable","default gateway not available","adapter disabled","network adapter disabled","nic driver","ip configuration error","dhcp failure"],"remoteActions":["DNS_RESET","ADAPTER_RESET","NIC_DRIVER_CHECK"],"visitReasons":[]}'::jsonb
WHERE source_id = 'as-rag-remote-local-network';

UPDATE as_rag_evidence
SET metadata = '{"keywords":["agent registration failed","registration failed","agent register failed","upload error","upload failed","last upload error","agent upload failure","auth 401","unauthorized","409 conflict","idempotency key","permission error","agent permission denied","config parse failed","config schema","service status","tray status","token status","agent install","agent restart","agent config","heartbeat problem"],"remoteActions":["CHECK_AGENT_CONFIG"],"visitReasons":[]}'::jsonb
WHERE source_id = 'as-rag-remote-agent';

UPDATE as_rag_evidence
SET metadata = '{"keywords":["startup app","startup impact","startup spike","background service","service crash loop","service restart","service restart count","service terminated unexpectedly","service control manager","idle high cpu","background task","background service pressure","시작프로그램","백그라운드 서비스"],"remoteActions":["CHECK_STARTUP_APPS"],"visitReasons":[]}'::jsonb
WHERE source_id = 'as-rag-remote-startup-service';

UPDATE as_rag_evidence
SET metadata = '{"keywords":["smart critical","predictive failure","bad block","has a bad block","disk has a bad block","disk i/o","i/o error","io error","ntfs error","filesystem error","drive disappeared","device disappeared","storage device instability","디스크 오류","배드블록","smart","저장장치 장애"],"remoteActions":[],"visitReasons":["STORAGE_REPLACEMENT_SUSPECTED"]}'::jsonb
WHERE source_id = 'as-rag-visit-disk-failure';

UPDATE as_rag_evidence
SET metadata = '{"keywords":["whea","whea logger","whea-logger","whea uncorrectable","bugcheck","bugcheck 0x","bsod","blue screen","stop code","hardware error","hardware error event","memory hardware","블루스크린","하드웨어 오류","메모리 오류"],"remoteActions":[],"visitReasons":["WHEA_ERROR_REPEAT","BSOD_REPEAT"]}'::jsonb
WHERE source_id = 'as-rag-visit-whea-bsod';

UPDATE as_rag_evidence
SET metadata = '{"keywords":["kernel-power","kernel power","event id 41","event 41","unexpected shutdown","previous system shutdown was unexpected","shutdown was unexpected","sudden shutdown","power loss","power loss under load","psu","전원 꺼짐"],"remoteActions":[],"visitReasons":["PSU_OR_POWER_PATH_RISK"]}'::jsonb
WHERE source_id = 'as-rag-visit-power-thermal';

UPDATE as_rag_evidence
SET metadata = '{"keywords":["thermal shutdown","fan rpm 0","fan not spinning","cooling fan stopped","thermal throttle","thermal throttling","overheat","overheating","fan failure","팬 미동작","과열"],"remoteActions":[],"visitReasons":["THERMAL_SERVICE_REQUIRED"]}'::jsonb
WHERE source_id = 'as-rag-visit-fan-thermal';

UPDATE as_rag_evidence
SET metadata = '{"keywords":["fps tuning","fps drops","game fps","valorant fps","game optimization","overclock","overclock stability","isp","router","printer","peripheral","data recovery","illegal software","physical damage","broken screen","water damage","오버클럭","공유기","프린터","주변기기","데이터 복구","물리 파손"],"remoteActions":[],"visitReasons":[],"blockingFactors":["UNSUPPORTED_SCOPE"]}'::jsonb
WHERE source_id = 'as-rag-unsupported-scope';
