DELETE FROM sys_permission
WHERE code IN ('review:read', 'review:approve', 'review:reject');
