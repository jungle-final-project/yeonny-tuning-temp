const CHROME_REMOTE_DESKTOP_SUPPORT_URL = 'https://remotedesktop.google.com/support';

export function SupportChatMessageContent({ content, className }: { content: string; className?: string }) {
  const parts = content.split(CHROME_REMOTE_DESKTOP_SUPPORT_URL);
  return (
    <p className={className}>
      {parts.map((part, index) => (
        <span key={`${part}-${index}`}>
          {part}
          {index < parts.length - 1 ? (
            <a
              className="font-black underline underline-offset-2"
              href={CHROME_REMOTE_DESKTOP_SUPPORT_URL}
              target="_blank"
              rel="noopener noreferrer"
            >
              Chrome Remote Desktop 열기
            </a>
          ) : null}
        </span>
      ))}
    </p>
  );
}
