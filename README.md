<div align="center">

<h1>JenaController</h1>
KBMS supporting web API-based queries and commands<br/><br/>

<picture><img src="https://img.shields.io/badge/-Kotlin-7F52FF?style=flat-square&logo=kotlin&logoColor=FFFFFF" alt="..."></picture>
<picture><img src="https://img.shields.io/badge/SpringBoot-6DB33F?style=flat-square&logo=springboot&logoColor=FFFFFF" alt="..."></picture>
<picture><img src="https://img.shields.io/badge/-ApacheJena-61a6f0?style=flat-square&logo=1&logoColor=FFFFFF" alt="..."></picture>

</div>

## Acknowledgment

This repository is intended solely for development purposes and not for deployment. The deployment repository is managed under the authority of the [DT-DL Lab]() at Dong-A University.

## Demo

<div align="center">

![Demo](https://github.com/user-attachments/assets/c9220444-66d2-4f6d-b8e3-0b1cba79f4cd)

</div>

## Description

This service provides web-based functionality to facilitate the use of ontologies with KBMS. It allows users to query SPARQL, store, update, and conduct experimental measurements according to their needs

## Function

- Aggregates pre-prepared OWL and instance RDF at runtime, passes them through a reasoner, verifies, and then loads them.
- Instance browser
- Executes queries mapped to routes or supports a webpage form using SELECT
- Functional operations such as storing and updating instance RDF
