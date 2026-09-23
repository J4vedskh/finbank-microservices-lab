# API Explorer

This read-only Swagger UI renders a public projection generated during the docs
build from the repository's canonical [OpenAPI 3.0 contract](api/openapi.yaml).
It covers the account, payment, and transaction APIs in one searchable view.
Internal payment-operations routes and authentication schemes remain available
only in the raw canonical contract and their dedicated reference sections.

!!! warning "Contract viewer only"
    The generated viewer specification contains no authentication schemes, and
    request submission is disabled. This page is not an API gateway, does not
    discover deployed services, and must never be used for production endpoints.
    The documented servers are local development examples.

[Download the raw OpenAPI YAML](api/openapi.yaml){ .md-button }

<div id="swagger-ui" data-spec-url="../api/openapi-public.yaml">
  <p>Loading the locally hosted API explorer...</p>
</div>

<noscript>
  JavaScript is required for the interactive contract viewer. The raw OpenAPI
  YAML link above remains available without JavaScript.
</noscript>
