UPDATE as_rag_evidence
SET metadata = jsonb_build_object(
  'keywords', jsonb_build_array(
    'display driver reset',
    'display driver stopped responding',
    'display driver',
    'gpu driver',
    'graphics driver',
    'nvlddmkm',
    'amdwddmg',
    'igfx',
    'igdkmdn64',
    'atikmdag',
    'event id 4101',
    'tdr',
    'device manager',
    'device setup manager',
    'device not migrated',
    'device not started',
    'code 43',
    'windowsupdateclient',
    'windows update failure',
    'windows update failed',
    'windows update error',
    'installation failure',
    'driver install failure',
    'driver failed to load',
    'driver package',
    'driver rollback',
    'rollback',
    'wudfrd failed to load',
    '드라이버 오류'
  ),
  'remoteActions', jsonb_build_array('DRIVER_ROLLBACK', 'WINDOWS_UPDATE_CHECK', 'DEVICE_RESET'),
  'visitReasons', jsonb_build_array()
)
WHERE source_id = 'as-rag-remote-driver-os';

UPDATE as_rag_evidence
SET metadata = jsonb_build_object(
  'keywords', jsonb_build_array(
    'app crash',
    'application crash',
    'faulting application',
    'application error',
    'application hang',
    'hung application',
    'fault bucket',
    'apphang',
    'launcher crash',
    'launcher',
    'runtime missing',
    '.net runtime',
    'sidebyside',
    'side-by-side',
    'activation context generation failed',
    'vcruntime',
    'msvcp',
    '0xc000007b',
    '0xc0000005',
    'msiinstaller',
    'windows installer',
    'installer error',
    'permission denied',
    'cache corruption'
  ),
  'remoteActions', jsonb_build_array('APP_REPAIR', 'RUNTIME_INSTALL', 'CACHE_CLEANUP'),
  'visitReasons', jsonb_build_array()
)
WHERE source_id = 'as-rag-remote-app-launcher';

UPDATE as_rag_evidence
SET metadata = jsonb_build_object(
  'keywords', jsonb_build_array(
    'free space low',
    'storage low',
    'low disk space',
    'memory pressure',
    'virtual memory',
    'low virtual memory',
    'resource-exhaustion-detector',
    'resource exhaustion detector',
    'diagnosed a low virtual memory condition',
    'pagefile',
    'pagefile usage',
    'disk active time',
    'disk queue length',
    'commit charge',
    'commit limit',
    'resource exhaustion',
    'available memory low'
  ),
  'remoteActions', jsonb_build_array('TEMP_FILE_CLEANUP', 'STARTUP_APP_REVIEW', 'PAGEFILE_CHECK'),
  'visitReasons', jsonb_build_array()
)
WHERE source_id = 'as-rag-remote-storage-memory';

UPDATE as_rag_evidence
SET metadata = jsonb_build_object(
  'keywords', jsonb_build_array(
    'dns failure',
    'dns timeout',
    'name resolution failed',
    'name resolution timed out',
    'dns client events',
    'dns-client',
    'gateway unreachable',
    'default gateway not available',
    'adapter disabled',
    'network adapter disabled',
    'nic driver',
    'netwtw',
    'e1rexpress',
    'ndis',
    'dhcp-client',
    'dhcp failure',
    'address lease',
    'ip configuration error',
    'duplicate address',
    'media disconnected',
    'limited connectivity'
  ),
  'remoteActions', jsonb_build_array('DNS_RESET', 'ADAPTER_RESET', 'NIC_DRIVER_CHECK'),
  'visitReasons', jsonb_build_array()
)
WHERE source_id = 'as-rag-remote-local-network';

UPDATE as_rag_evidence
SET metadata = jsonb_build_object(
  'keywords', jsonb_build_array(
    'agent registration failed',
    'registration failed',
    'agent register failed',
    'upload error',
    'upload failed',
    'last upload error',
    'agent upload failure',
    'auth 401',
    'unauthorized',
    '403 forbidden',
    '409 conflict',
    'idempotency key',
    'permission error',
    'agent permission denied',
    'config parse failed',
    'config schema',
    'token expired',
    'token invalid',
    'service status',
    'tray status',
    'token status',
    'agent install',
    'agent restart',
    'agent config',
    'heartbeat problem',
    'heartbeat timeout'
  ),
  'remoteActions', jsonb_build_array('CHECK_AGENT_CONFIG'),
  'visitReasons', jsonb_build_array()
)
WHERE source_id = 'as-rag-remote-agent';

UPDATE as_rag_evidence
SET metadata = jsonb_build_object(
  'keywords', jsonb_build_array(
    'startup app',
    'startup impact',
    'startup spike',
    'background service',
    'service crash loop',
    'service restart',
    'service restart count',
    'service terminated unexpectedly',
    'service control manager',
    'service failed to start',
    'service did not respond',
    'timeout was reached',
    'event id 7000',
    'event id 7009',
    'event id 7011',
    'event id 7031',
    'event id 7034',
    'idle high cpu',
    'background task',
    'background service pressure',
    '시작프로그램',
    '백그라운드 서비스'
  ),
  'remoteActions', jsonb_build_array('CHECK_STARTUP_APPS'),
  'visitReasons', jsonb_build_array()
)
WHERE source_id = 'as-rag-remote-startup-service';

UPDATE as_rag_evidence
SET metadata = jsonb_build_object(
  'keywords', jsonb_build_array(
    'smart critical',
    'predictive failure',
    'bad block',
    'has a bad block',
    'disk has a bad block',
    'disk i/o',
    'i/o error',
    'io error',
    'io operation at logical block',
    'reset to device',
    'controller error',
    'storahci',
    'iastora',
    'ntfs error',
    'file system structure',
    'filesystem error',
    'drive disappeared',
    'device disappeared',
    'storage device instability',
    '디스크 오류',
    '배드블록',
    'smart',
    '저장장치 장애'
  ),
  'remoteActions', jsonb_build_array(),
  'visitReasons', jsonb_build_array('STORAGE_REPLACEMENT_SUSPECTED')
)
WHERE source_id = 'as-rag-visit-disk-failure';

UPDATE as_rag_evidence
SET metadata = jsonb_build_object(
  'keywords', jsonb_build_array(
    'whea',
    'whea logger',
    'whea-logger',
    'whea uncorrectable',
    'corrected hardware error',
    'fatal hardware error',
    'bugcheck',
    'bugcheck 0x',
    '0x00000124',
    'bsod',
    'blue screen',
    'stop code',
    'hardware error',
    'hardware error event',
    'machine check',
    'livekernelevent',
    'memory hardware',
    '블루스크린',
    '하드웨어 오류',
    '메모리 오류'
  ),
  'remoteActions', jsonb_build_array(),
  'visitReasons', jsonb_build_array('WHEA_ERROR_REPEAT', 'BSOD_REPEAT')
)
WHERE source_id = 'as-rag-visit-whea-bsod';

UPDATE as_rag_evidence
SET metadata = jsonb_build_object(
  'keywords', jsonb_build_array(
    'kernel-power',
    'kernel power',
    'event id 41',
    'event 41',
    'unexpected shutdown',
    'previous system shutdown was unexpected',
    'rebooted without cleanly shutting down',
    'shutdown was unexpected',
    'sudden shutdown',
    'lost power unexpectedly',
    'power loss',
    'power loss under load',
    'bugcheckcode 0',
    'psu',
    'power supply',
    '전원 꺼짐'
  ),
  'remoteActions', jsonb_build_array(),
  'visitReasons', jsonb_build_array('PSU_OR_POWER_PATH_RISK')
)
WHERE source_id = 'as-rag-visit-power-thermal';

UPDATE as_rag_evidence
SET metadata = jsonb_build_object(
  'keywords', jsonb_build_array(
    'thermal shutdown',
    'fan rpm 0',
    'fan not spinning',
    'cooling fan stopped',
    'thermal throttle',
    'thermal throttling',
    'thermal event',
    'processor speed is being limited',
    'system firmware',
    'acpi thermal zone',
    'overheat',
    'overheating',
    'fan failure',
    '팬 미동작',
    '과열'
  ),
  'remoteActions', jsonb_build_array(),
  'visitReasons', jsonb_build_array('THERMAL_SERVICE_REQUIRED')
)
WHERE source_id = 'as-rag-visit-fan-thermal';

UPDATE as_rag_evidence
SET metadata = jsonb_build_object(
  'keywords', jsonb_build_array(
    'boot failure',
    'cannot boot',
    'no boot',
    'os boot failure',
    'automatic repair',
    'startup repair',
    'winre',
    'boot critical',
    'device offline',
    'heartbeat missing',
    'heartbeat gap',
    'remote unavailable',
    'remote connection unavailable',
    'quick assist unavailable',
    'quick assist failed',
    '부팅 불가',
    '원격 연결 불가'
  ),
  'remoteActions', jsonb_build_array(),
  'visitReasons', jsonb_build_array('DEVICE_OFFLINE', 'REMOTE_HELP_NOT_AVAILABLE')
)
WHERE source_id = 'as-rag-visit-boot-remote-blocked';

UPDATE as_rag_evidence
SET metadata = jsonb_build_object(
  'keywords', jsonb_build_array(
    'fps tuning',
    'fps drops',
    'frame time',
    'frame-time',
    'game fps',
    'valorant fps',
    'game optimization',
    'overclock',
    'overclock stability',
    'isp',
    'router',
    'printer',
    'peripheral',
    'data recovery',
    'file recovery',
    'illegal software',
    'physical damage',
    'broken screen',
    'water damage',
    'liquid damage',
    '오버클럭',
    '공유기',
    '프린터',
    '주변기기',
    '데이터 복구',
    '물리 파손'
  ),
  'remoteActions', jsonb_build_array(),
  'visitReasons', jsonb_build_array(),
  'blockingFactors', jsonb_build_array('UNSUPPORTED_SCOPE')
)
WHERE source_id = 'as-rag-unsupported-scope';
