<div align="center">

<h1>Jenavi</h1>
KBMS supporting web API-based queries and commands<br/><br/>

<picture><img src="https://img.shields.io/badge/-Kotlin-7F52FF?style=flat-square&logo=kotlin&logoColor=FFFFFF" alt="..."></picture>
<picture><img src="https://img.shields.io/badge/SpringBoot-6DB33F?style=flat-square&logo=springboot&logoColor=FFFFFF" alt="..."></picture>
<picture><img src="https://img.shields.io/badge/-ApacheJena-61a6f0?style=flat-square&logo=1&logoColor=FFFFFF" alt="..."></picture>

</div>

## Acknowledgment

This repository is intended solely for development purposes and not for deployment. The deployment repository is managed under the authority of the [DT-DL Lab](https://aidtlab-dau.github.io/) at Dong-A University.

## Demo

<div align="center">

![Demo](https://github.com/user-attachments/assets/4994e7a3-a8d9-4c64-94c6-f0248041d67f)

</div>

## Description

Jenavi is a custom API web service built entirely on Apache Jena, operating independently without relying on Fuseki. This service provides web-based functionality to facilitate the use of ontologies with Knowledge Base Management Systems (KBMS). It allows users to query SPARQL, store, update, and conduct experimental measurements according to their needs by handling semantic web data and RDF models through a lightweight interface.

## Features

- **Storage Mode Selection (Experimental)**
  - Choose between **TDB** (disk-based storage for large datasets) or **On-Memory** (for fast processing and experimental testing).
- **RDF Data Management**
  - **Upload:** Easily upload external RDF files into the system.
  - **Initialization:** Clear and reset all currently loaded information in the storage to a clean state.
  - **Aggregation & Reasoning:** Aggregates pre-prepared OWL and instance RDF at runtime, passes them through a reasoner, verifies, and then loads them.
  - **Functional Operations:** Store and update instance RDF data as needed.
- **SPARQL Query Execution**
  - Executes queries mapped to specific routes or supports a webpage form using SELECT to retrieve and return data.
- **Data Browser**
  - A built-in instance browser to intuitively check the currently loaded triples and graph structures.
