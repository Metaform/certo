# Supply Chain Core Messaging

Supply Chain Core Messaging (Core Messaging) defines a protocol-agnostic foundation for event-based message exchange
between supply chain participants, built on the CloudEvents specification [[cloudevents]]. It specifies the base event
structure, attribute requirements, and extension mechanisms used by protocol specifications that build on it without
binding to a specific transport protocol.

Specifications that build on this document are termed <dfn data-lt="Composing Specifications">Composing
Specification</dfn>s. A [=Composing Specification=] defines concrete message types, payloads, and interaction sequences
in terms of the event structure defined here.

## Terminology

The following terms are used to describe concepts in this specification.

- <dfn>Event</dfn>: A data record expressing an occurrence and its context, serialized as defined by this
  specification.
- <dfn>Producer</dfn>: The system or process that creates [=Events=].
- <dfn>Consumer</dfn>: The system or process that receives and processes [=Events=].
- <dfn>Participant</dfn>: An organization that exchanges [=Events=] with other organizations using a protocol defined
  by a [=Composing Specification=].
- <dfn>Source</dfn>: An identifier for the context in which an [=Event=] occurred.
- <dfn>CloudEvents</dfn>: The CNCF specification for describing events in a common format [[cloudevents]].

## Conformance

The keywords MAY, MUST, MUST NOT, OPTIONAL, RECOMMENDED, REQUIRED, SHOULD, and SHOULD NOT in this document are to be
interpreted as described in BCP 14 [[rfc2119]] [[rfc8174]] when, and only when, they appear in all capitals, as shown
here.

A conforming implementation:

- MUST produce [=Events=] that conform to version 1.0 of the [=CloudEvents=] specification [[cloudevents]]
- MUST support [=Event=] serialization in JSON format as defined in [JSON Format](#json-format)
- MUST include all REQUIRED attributes as defined in [Required Attributes](#required-attributes)

[=Composing Specifications=] MUST provide JSON schemas for the [=Event=] types they define.

This specification cites [=CloudEvents=] v1.0.2 as the reference edition of the 1.0 series. Implementations SHOULD
track minor (backward-compatible) revisions of the 1.0 series as they are published.

## Event Specification

### Base Event Structure

All [=Events=] conforming to this specification MUST be valid [=CloudEvents=] version 1.0 events [[cloudevents]].

#### Required Attributes

The following [=CloudEvents=] attributes are REQUIRED:

| Attribute     | Type          | Description                                                                                                     |
|---------------|---------------|-----------------------------------------------------------------------------------------------------------------|
| `id`          | String        | Unique identifier for the [=Event=]. [=Producers=] MUST ensure that `source` + `id` is unique for each distinct [=Event=] |
| `source`      | URI-reference | Identifies the [=Producer=] context. The value SHOULD be the [=Participant=] identity of the [=Producer=], for example a decentralized identifier (DID) [[did-core]] |
| `specversion` | String        | [=CloudEvents=] specification version. MUST be "1.0"                                                            |
| `type`        | String        | [=Event=] type identifier. MUST be a non-empty string that follows the [type naming convention](#type-naming-convention) |

#### Recommended Attributes

The following [=CloudEvents=] attributes are RECOMMENDED:

| Attribute         | Type      | Description                                                          |
|-------------------|-----------|----------------------------------------------------------------------|
| `time`            | Timestamp | [[rfc3339]] timestamp of when the [=Event=] occurred                 |
| `datacontenttype` | String    | Media type of the data payload (e.g., "application/json")            |
| `dataschema`      | URI       | URI identifying the schema of the data payload                       |

If `datacontenttype` is not specified, the [=Event=] MUST be interpreted as `application/json`.

#### Optional Attributes

| Attribute | Type   | Description                                                         |
|-----------|--------|---------------------------------------------------------------------|
| `subject` | String | Subject of the [=Event=] in the context of the [=Event=] [=Source=] |

#### Optional Extension Attributes

This specification defines the following extension attribute:

| Attribute             | Type   | Description                                                                                                                  |
|-----------------------|--------|------------------------------------------------------------------------------------------------------------------------------|
| `dataspaceidentifier` | String | The identifier assigned to the [=Producer=] [=Participant=] within the dataspace context in which the [=Event=] is exchanged |

The `dataspaceidentifier` attribute is used when a dataspace assigns [=Participant=] identifiers that are distinct
from the [=Participant=] identity conveyed by the `source` attribute. [=Composing Specifications=] MAY require this
attribute.

### Identifier Requirements

[=Event=] IDs MUST be unique within the scope of a `source`:

- The combination of `source` + `id` MUST uniquely identify an [=Event=]
- Duplicate [=Events=] (e.g., retries) MUST reuse the same `source` and `id`
- [=Event=] IDs MUST be UUIDs as defined by [[rfc9562]]

Note that [=Consumers=] MUST NOT assume which UUID version a given `id` uses. The `id` is to be treated as an opaque
identifier and used only for equality comparison; its internal structure (version, timestamp, or any other embedded
data) MUST NOT be interpreted.

### Duplicate Events

[=Events=] may be delivered more than once, for example, as the result of a retry. [=Consumers=] MUST treat
[=Events=] with an identical `source` and `id` as duplicates of the same [=Event=]. [=Event=] processing MUST be
idempotent, or duplicate [=Events=] MUST be discarded.

### Event Ordering

This specification provides no delivery-order guarantees. [=Consumers=] MUST NOT infer [=Event=] order from the
`time` attribute or from arrival order. [=Composing Specifications=] MAY define ordering semantics for the [=Event=]
types they define.

### Event Types

#### Type Naming Convention

[=Event=] types MUST follow reverse-DNS notation:

`<domain>.<protocol>.<specifier>.<version>`

where:

- The `domain` and `protocol` segments MUST be in lowercase
- The `specifier` MUST be in Camel Case
- The `version` MUST follow the [type versioning](#type-versioning) rules

[=Event=] types defined by [=Composing Specifications=] MUST use the domain `org.dsaf`. For example, a fictitious
`protoapps` specification would define types as follows:

```
org.dsaf.protoapps.ExampleEvent.v1
```

> NOTE: The `org.dsaf` domain is a placeholder and will be finalized before this specification is released.

Third parties MAY define additional [=Event=] types. Third-party types MUST use a reverse-DNS domain under the control
of the defining party and MUST NOT use the `org.dsaf` domain.

#### Type Versioning

Version information MUST be encoded as follows:

`v<major>`

for example `v1`. Minor and patch versions MUST NOT be included in the type name.

### Event Data

#### Data Payload

The `data` attribute MAY contain domain-specific information of a valid JSON type (object, array, string, number,
boolean, null). The `data` attribute SHOULD have a schema defined via the `dataschema` attribute.

#### Data Content Type

When `data` is present, `datacontenttype` SHOULD be specified. If specified, it MUST be a valid media type as defined
by [[rfc2046]]. If `data` is present and `datacontenttype` is not specified, implementations MUST default to
interpreting the data payload as "application/json". If `data_base64` is present and `datacontenttype` is not
specified, implementations MUST default to interpreting the data payload as "application/octet-stream".

### Extension Attributes

#### Defining Extensions

Custom attributes MAY be added following [=CloudEvents=] naming rules:

- The attribute name MUST consist of lowercase letters (a-z) or digits (0-9)
- The attribute name MUST be at least one character and SHOULD NOT exceed 20 characters
- The attribute name SHOULD start with a letter
- The attribute name MUST NOT be `data`, `data_base64`, or any [=CloudEvents=] reserved attribute name

#### Extension Attribute Types

Extension attributes MUST use the [=CloudEvents=] type system:

- Boolean
- Integer
- String
- Binary
- URI
- URI-reference
- Timestamp

#### Processing Extension Attributes

[=Consumers=] MUST ignore extension attributes they do not recognize and MUST NOT reject an [=Event=] because it
contains unrecognized extension attributes.

## Data Formats

### JSON Format

All implementations MUST support JSON serialization per the JSON Event Format for CloudEvents [[cloudevents-json]].

### JSON Schema

The base [=Event=] structure is defined by the [Event JSON Schema](./schemas/event.schema.json). An [=Event=] MUST
contain at most one of the `data` and `data_base64` attributes.

## HTTPS Binding

[=Events=] transmitted over HTTPS MUST follow the HTTP Protocol Binding for CloudEvents [[cloudevents-http]].

When using HTTPS and non-binary content, [=Events=] MUST be serialized in JSON format using the JSON structured mode.

### Batches

Batches of [=Events=] MAY be transmitted as defined by the JSON batch format [[cloudevents-json]]. The following
rules apply to batches:

- The order of [=Events=] within a batch is not significant and MUST NOT be relied upon
- An endpoint MUST accept or reject a batch as a unit; if any [=Event=] in a batch is invalid, the endpoint MUST
  reject the entire batch
- Acceptance of a batch acknowledges receipt of its [=Events=]; it does not indicate that the [=Events=] have been
  processed

### Security and Authorization

All endpoints MUST use HTTPS, that is, HTTP over TLS 1.2 or higher. Authorization mechanisms, including token formats
and acquisition, are defined by [=Composing Specifications=].

If a client is not authorized for an endpoint request, the endpoint MUST return `401 Unauthorized` or `404 Not Found`.

[=Event=] authenticity and integrity derive from the secured transport channel and the authorization mechanism.
Message-level signatures are out of scope for this specification and MAY be defined by [=Composing Specifications=].

## Examples

*This section is non-normative.*

### Single Event

The following example includes the OPTIONAL `dataspaceidentifier` extension attribute:

```json
{
  "specversion": "1.0",
  "type": "org.dsaf.protoapps.ExampleEvent.v1",
  "id": "e678a546-8bb7-4491-8765-046fa39ac76c",
  "source": "did:web:example.com",
  "dataspaceidentifier": "PRT000000001234",
  "time": "2026-02-27T12:34:56.789Z",
  "datacontenttype": "application/json",
  "dataschema": "https://example.com/schemas/request.json",
  "subject": "request/12345",
  "data": {
    "requestId": "12345",
    "status": "ready"
  }
}
```

### Batch of Events

```json
[
  {
    "specversion": "1.0",
    "type": "org.dsaf.protoapps.ExampleEvent.v1",
    "id": "e678a546-8bb7-4491-8765-046fa39ac76c",
    "source": "did:web:example.com",
    "time": "2026-02-27T12:34:56.789Z",
    "datacontenttype": "application/json",
    "dataschema": "https://example.com/schemas/request.json",
    "subject": "request/12345",
    "data": {
      "requestId": "12345",
      "status": "ready"
    }
  },
  {
    "specversion": "1.0",
    "type": "org.dsaf.protoapps.ExampleEvent.v1",
    "id": "2b51c8ed-2f06-4220-a454-52bd3d1b4797",
    "source": "did:web:example.com",
    "time": "2026-02-27T12:34:56.789Z",
    "datacontenttype": "application/json",
    "dataschema": "https://example.com/schemas/request.json",
    "subject": "request/67890",
    "data": {
      "requestId": "67890",
      "status": "updated"
    }
  }
]
```

## References

*Note: when this document is converted to ReSpec, inline `[[citation]]` references will be resolved automatically via
SpecRef, and this section will be generated.*

### Normative References

<a id="cloudevents"></a>
**[cloudevents]** Cloud Native Computing Foundation, "CloudEvents 1.0.2 — Core Specification",
<https://github.com/cloudevents/spec/blob/v1.0.2/cloudevents/spec.md>.

<a id="cloudevents-http"></a>
**[cloudevents-http]** Cloud Native Computing Foundation, "HTTP Protocol Binding for CloudEvents 1.0.2",
<https://github.com/cloudevents/spec/blob/v1.0.2/cloudevents/bindings/http-protocol-binding.md>.

<a id="cloudevents-json"></a>
**[cloudevents-json]** Cloud Native Computing Foundation, "JSON Event Format for CloudEvents 1.0.2",
<https://github.com/cloudevents/spec/blob/v1.0.2/cloudevents/formats/json-format.md>.

<a id="rfc2046"></a>
**[rfc2046]** Freed, N. and Borenstein, N., "Multipurpose Internet Mail Extensions (MIME) Part Two: Media Types",
RFC 2046, November 1996, <https://www.rfc-editor.org/rfc/rfc2046>.

<a id="rfc2119"></a>
**[rfc2119]** Bradner, S., "Key words for use in RFCs to Indicate Requirement Levels", BCP 14, RFC 2119, March 1997,
<https://www.rfc-editor.org/rfc/rfc2119>.

<a id="rfc3339"></a>
**[rfc3339]** Klyne, G. and Newman, C., "Date and Time on the Internet: Timestamps", RFC 3339, July 2002,
<https://www.rfc-editor.org/rfc/rfc3339>.

<a id="rfc8174"></a>
**[rfc8174]** Leiba, B., "Ambiguity of Uppercase vs Lowercase in RFC 2119 Key Words", BCP 14, RFC 8174, May 2017,
<https://www.rfc-editor.org/rfc/rfc8174>.

<a id="rfc9562"></a>
**[rfc9562]** Davis, K., Peabody, B., and Leach, P., "Universally Unique IDentifiers (UUIDs)", RFC 9562, May 2024,
<https://www.rfc-editor.org/rfc/rfc9562>.

### Non-Normative References

<a id="cloudevents-primer"></a>
**[cloudevents-primer]** Cloud Native Computing Foundation, "CloudEvents Primer",
<https://github.com/cloudevents/spec/blob/main/cloudevents/primer.md>.

<a id="did-core"></a>
**[did-core]** W3C, "Decentralized Identifiers (DIDs) v1.0", W3C Recommendation, July 2022,
<https://www.w3.org/TR/did-core/>.
