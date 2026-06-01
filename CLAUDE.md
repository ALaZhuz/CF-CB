# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**cf-cb** is a Spring Boot service that integrates Codebeamer (ALM tool) with DingTalk notifications. It handles tracker item traceability and sends automated notifications based on workflow state changes.

## Session Initialization

When starting a new session, read the following documents to understand project context:

1. All spec files in `openspec/specs/` directory
2. `src/main/resources/static/requirements.md` - Notification system requirements
3. `src/main/resources/static/项目说明文档.md` - Project overview documentation

## Build & Test Commands

```bash
# Build the project
mvn clean package

# Run tests
mvn test

# Run a single test class
mvn test -Dtest=AppTest

# Run the application
mvn spring-boot:run

# Run the packaged jar
java -jar target/cf-cb.jar
```

## Architecture

### Layer Structure

- **Controller** (`org.example.controller`): REST endpoints, currently `/tracker/*`
- **Service** (`org.example.service.impl`): Business logic
  - `TrackerServiceImpl`: Core traceability logic - builds upstream references between tracker items
  - `CBSwaggerServiceImpl`: Codebeamer API client wrapper
  - `DingServiceImpl`: DingTalk notification client
- **Config** (`org.example.config`): Configuration binding and HTTP helpers
- **Model** (`org.example.model`): DTOs and domain objects

### Key Components

**CodeBeamerHttpHelper**: Provides Basic Auth headers for all Codebeamer API calls. Credentials come from `codebeamer.username` and `codebeamer.password` in application.yml.

**CBSwaggerService**: Wraps Codebeamer REST API v3:
- `query()`: cbQL query for items
- `getAllTrackerItems()`: Paginated fetch of tracker items
- `getRelationId()`: Fetch item relations with pagination (OUTGOING_ASSOCIATIONS, UPSTREAM_REFERENCES, INCOMING_ASSOCIATIONS)
- `getTrackerConfiguration()`: Get tracker config, finds "追溯" (traceability) field by label match
- `putTrackerItemField()`: Update custom field on tracker item

**TrackerService**: Two main operations:
1. `getDownstreamReferences()`: Returns downstream items grouped by project/tracker
2. `updateField()`: Builds upstream traceability - complex flow traversing project → pool → upstream → incoming associations

### Relation Types (RelationType enum)

The system tracks three relation types between tracker items:
- `OUTGOING_ASSOCIATIONS`: Copy-from relation (项目 → 池子)
- `UPSTREAM_REFERENCES`: Upstream traceability (池子 → 上游需求)
- `INCOMING_ASSOCIATIONS`: Copy-to relation (上游需求 → 项目)

### Configuration

application.yml has two main config sections:
- `codebeamer`: base-url, username, password (multiple environments commented)
- `ding`: corp-id, app-key, app-secret, agent-id, various URLs for notifications

Environment switching is done by uncommenting the relevant codebeamer config block.

## Pagination Pattern

Several API calls use pagination (pageSize=500 max). Pattern:
```java
int page = 1;
while (true) {
    Response response = apiCall(page, pageSize);
    if (response == null || response.getItems().isEmpty()) break;
    allItems.addAll(response.getItems());
    if (allItems.size() >= response.getTotal()) break;
    page++;
}
```

## API Endpoints

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/tracker/downstreamReferences` | POST | Get downstream items grouped by project |
| `/tracker/field` | POST | Build upstream traceability for tracker items |

Both accept `TrackerItemRequest` with `trackerId` and optional `items` list (empty = full fetch).

## Notes

- DingProperties class is referenced in DingServiceImpl but needs to be created (extends the pattern from CBProperties)
- Requirements document at `src/main/resources/static/requirements.md` describes planned notification system with scheduled tasks, SQLite persistence, and YAML-based workflow configuration