package com.originguard.investigation.domain;

import java.util.UUID;

public record AssignableUser(UUID id, String username, String displayName, String role) {}
