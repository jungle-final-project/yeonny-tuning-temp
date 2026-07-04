UPDATE users
SET password_hash = '$2a$10$MD8WYrm/3tXHCRNCCtUiH.TuIoQzGBaDZmMlpWCtT0eTsnxLT8Tly',
    updated_at = now()
WHERE email IN ('admin@example.com', 'user@example.com')
  AND deleted_at IS NULL;
