# 1. Roles

| Name | Responsibilities | Current Assignees
|------|------------------|------------------
| User | Report the issues in the [expected way](#5-issue-reporting) | Definitely unknown
| Developer | <ul><li>Development</li><li>Testing</li><li>Automation</li><li>Documentation</li></ul> | <ul><li>Veronika Kochugova</li><li>Andrey Kurilov</li><ul>
| Owner | <ul><li>[*Next* version scope](#3-scopes) definition</li><li>Roadmap definition</li><li>User interaction</li></ul> | Andrey Kurilov
| Manager | The explicit scopes approval | ********

# 2. Versions

## 2.1. Backward Compatibility

The following interfaces are mentioned as the subject of the backward compatibility:
1. Input (item list files, scenario files, configuration options)
2. Output files containing the metrics
3. API

Mongoose uses the [semantic versioning](http://semver.org/). This means that the ***X.Y.Z*** version notation is used:

* ***X***
    Major version number. Points to significant design and interfaces change. The backward compatibility is not
    guaranteed.

* ***Y***
    Minor version number. The *backward compatibility* is guaranteed.

* ***Z***
    Patch version number. Includes only the defect fixes.

# 3. Tasks

## 3.1. States

| State     | Description |
|-----------|-------------|
| OPEN      | All new tasks should have this state. The tasks are selected from the set of the *OPEN* tasks for the proposal and review process. The task is updated w/ the corresponding comment but left in the *OPEN* state if it's considered incomplete/incorrect. Also incomplete/incorrect task should be assigned back to the reporter.
| PROPOSED  | The task is selected for the approval by the *manager*.
| DEFERRED  | Manager has approved the task to be processed after the next major/minor (non-patch) version is released.
| ACCEPTED  | Manager approved the task to be processed before the next major/minor (non-patch) version is released.
| ESCALATED | Critical defect which interrupts all *DEFERRED*/*ACCEPTED* tasks processing. Causes the new *patch* version release ASAP.
| RESOLVED  | Task is done and the corresponding changes are merged into the `integration` branch.
| CLOSED    | Task is done and the corresponding changes are merged into the `master` branch (= version release, availability for the user).

**Note**:
> The corresponding impact probability/frequency is not taken into account in the process currently. For example, all
> defects are assumed to be equally frequently occurring and affecting same users, regardless the particular
> scenario/use case. This approach is used due to the lack of the sufficient statistical information about the Mongoose
> usage.

## 3.2. Types

| Type     | Description          | Specific Properties |
|----------|----------------------|---------------------|
| Defect   | <ul><li>Crash</li><li>Hang</li><li>Not functioning</li><li>Functioning incorrectly</li><li>Performance degradation</li><li>Non-critical defect</li><li>A defect w/ the workaround available for the users</li><li>etc</li></ul> | <ul><li>Affected version</li><li>Fix version</li><li>Start command/request</li><li>Scenario</li><li>Steps</li><li>Expected behaviour</li><li>Observed behaviour</li></ul>
| Story    | High-level use cases | <ul><li>Purpose</li><li>Requirements</li><li>Limitations</li></ul>
| Task     |                      | <ul><li>Version</li><li>Description</li>
| Sub-task |                      | <ul><li>Version</li><li>Description<li>

## 3.3. Specific Properties

| Name                  | Applicable task type | Who is responsible to specify  | Notes
|-----------------------|----------------------|--------------------------------|-------|
| Affected version      | Defect               | Reporter: user/developer/owner | Only the *latest* version may be used for the defect reporting. The task should be *rejected* if the reported version is not *latest*.
| Fix version           | Defect               | Reviewer: developer/owner      |
| Start command/request | Defect               | Reporter: user/developer/owner | Leave only the essential things to reproduce: try to check if possible if the bug is reproducible w/o distributed mode, different concurrency level, item data size, etc.
| Scenario              | Defect               | Reporter: user/developer/owner | Don't clutter with large scenario files. Simplify the scenario leaving only the essential things.
| Steps                 | Defect               | Reporter: user/developer/owner |
| Expected behaviour    | Defect               | Reporter: user/developer/owner | The reference to the particular documentation part describing the expected behavior is preferable.
| Observed behaviour    | Defect               | Reporter: user/developer/owner | Error message, errors.log output file, etc.
| Purpose               | Story                | Reporter: user/developer/owner | Which particular problem should be solved with Mongoose? The links to the related documents and literature are encouraged.
| Requirements          | Story                | Reporter: user/developer/owner | Both functional and performance requirements are mandatory. Optionally the additional requirements/possible enhancements may be specified.
| Limitations           | Story                | Reviewer: developer/owner      |
| Version               | Task/Sub-task        | Reviewer: developer/owner      |
| Description           | Task/Sub-task        | Reporter: user/developer/owner |