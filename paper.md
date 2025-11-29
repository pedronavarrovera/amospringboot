--- 
title: "Quantum-AI-Guarded Cyber Currency for Intangible Tokens"
tags:
  - AI-driven cybersecurity
  - amo
  - blockchain protocols
  - Dijkstra's Algorithm
  - Distributed Systems
  - Financial Networks
  - Graph Theory
  - Java
  - JSON API
  - Matrix Analysis
  - Post-Quantum Cryptography
  - quantum encryption
  - Social Networks
  - Spring Boot
  - Matrix Service
  - API
authors:
  - name: "Pedro Juan Navarro Vera"
    orcid: "https://orcid.org/0009-0009-5614-8024"
    affiliation: "1"
corresponding_author:
  email: "info@cybereu.eu"
affiliations:
  - name: "Independent Researcher"
    index: 1
date: 2025-02-01
bibliography: paper.bib
---

# Summary

**AMO-Spring**, powering the *Quantum-AI-Guarded Cyber Currency for Intangible Tokens* model, is a production-ready software framework for analyzing and transforming matrix-based representations of social and financial networks. It supports cycle detection, payment-chain reduction, condonation workflows, and matrix analysis through a JSON-only API designed for reproducible research and real-time simulation.

The system is publicly deployed at:

- **Production UI:** https://payment.amo.onl/  
- **Production API (Swagger):** https://api.amo.onl/docs  

Two demo accounts are provided for evaluation:

- **Username:** `Victor8405@azuretestepeuropa.onmicrosoft.com`  
- **Password:** `Welcome2Terracota!`

- **Username:** `Kiko3494@azuretestepeuropa.onmicrosoft.com`  
- **Password:** `Welcome2Terracota!`

A major extension planned for **Q3 2026** adds hybrid **ML-KEM + AES post-quantum encryption** to secure matrix blobs, payment requests, and cyber-currency flows.

# Statement of Need

Matrix and graph-structured models are central to the study of social networks, distributed economic systems, cyber-currencies, and obligation networks. Existing computational tools such as NetworkX and igraph excel at local analysis but do not provide:

- a **production-grade API** for experiments,  
- a **public reproducible environment**,  
- mechanisms for **cycle detection and application** in obligation networks,  
- automated **condonation workflows**, or  
- integration paths toward **quantum-secure operations**.

Conversely, enterprise-grade frameworks rarely incorporate research-oriented graph theory operations.

**AMO-Spring bridges these gaps**, delivering:

- a live, stable, reproducible API,  
- automated matrix ingestion and export,  
- cycle-based payment minimization,  
- condonation email workflows, and  
- a roadmap for post-quantum cyber-currency modeling.

# Software Description

The system comprises two coordinated components:

### 1. AMOSpringBoot (Frontend Web Application)
- Accessible at: **https://payment.amo.onl/**
- Provides user interface, authentication, and workflow orchestration.
- Interacts entirely with the Matrix Service API.

### 2. AMOAPI (Matrix Service, JSON-only)
- Live documentation: **https://api.amo.onl/docs**
- Repository: https://github.com/pedronavarrovera/amoapi
- Implements:
  - matrix blob storage and retrieval,
  - cycle detection,
  - cycle application for payment reduction,
  - payment flow simulation,
  - automated condonation emails.

All communication is JSON-based, ensuring transparent reproducibility.

# API Overview

The production API implements the following endpoints:

### Health
- `GET /` — Root service banner  
- `GET /health` — System health  
- `GET /_health/igraph` — igraph subsystem health  

### Matrix Operations
- `GET /matrix/blobs` — List blobs  
- `GET /matrix/download?id=...` — Download a blob  
- `GET /matrix/analyze?id=...` — Analyze blob (cycle presence, metrics)  

### Payments & Cycles
- `POST /matrix/payment` — Execute payment logic  
- `POST /matrix/cycle/find` — Detect cycles  
- `POST /matrix/cycle/apply` — Apply cycle reduction  

### Email Workflows
- `POST /matrix/email/condonation` — Send condonation email automatically  

These operations support simulation of obligation networks, automated cycle elimination, and matrix-driven decision automation.

# Architecture

A conceptual architecture is illustrated in Figure 1:

```
User Browser → AMOSpringBoot (payment.amo.onl)
          ↓ REST API
AMOAPI Matrix Service (api.amo.onl)
          ↓
     Database Layer

Future (Q3 2026):
    +→ ML-KEM + AES Quantum-Safe Encryption Module
```

**Figure 1: High-level architecture of the AMO-Spring system.**

# Planned Post-Quantum Module (Q3 2026)

The upcoming module integrates **ML-KEM-512 (Kyber)** for key exchange and
**AES-256-GCM** for authenticated symmetric encryption. It enables:

- encrypted matrix blobs,  
- secure payment-simulation payloads,  
- PQC-protected condonation workflows,  
- experimental frameworks for quantum-resistant cyber-currency systems.

# Example Use Cases

- Debt-netting and obligation simplification  
- Cycle detection in financial networks  
- Distributed ledger and cyber-currency modeling  
- Automated condonation and email-driven workflows  
- AI-enhanced cybersecurity simulations  
- Matrix-based economic modeling  
- Quantum-resistant transaction analysis  

# Availability

- **Web Application:** https://payment.amo.onl/  
- **Matrix Service API:** https://api.amo.onl/docs  
- **AMOSpringBoot Source:** https://github.com/pedronavarrovera/amospringboot  
- **AMOAPI Source:** https://github.com/pedronavarrovera/amoapi  

# Software License

This software is released under the **Apache License 2.0**, a permissive
open-source license that allows reuse, modification, and distribution.  
The full license text is available in the project repositories:

- https://github.com/pedronavarrovera/amospringboot  
- https://github.com/pedronavarrovera/amoapi

# Acknowledgements

This work draws on graph theory, cycle elimination literature, economic network
analysis, AI-driven cybersecurity principles, and the NIST Post-Quantum
Cryptography standardization project.

# References

See `paper.bib` for a complete bibliography.
