# SedStart Runner Jenkins Plugin

## Introduction

The **SedStart Runner** plugin allows you to trigger and monitor automated test runs
created on the [SedStart](https://sedstart.com/) platform directly from Jenkins.

It supports:
- Running tests in **Cloud mode** via SedStart APIs
- Running tests in **Local mode** using the SedStart CLI on Jenkins agents
- Streaming live execution logs into the Jenkins console

---

## Requirements

- Jenkins 2.516+
- A SedStart account: https://sedstart.com/
- API access enabled for your project

---

## Getting Started

### 1. Generate an API Key

1. Log in to https://app.sedstart.com
2. Open your project
3. Navigate to **Settings → API Keys**
4. Generate a new API key

---

### 2. Store API Key securely in Jenkins (recommended)

Store the API key using Jenkins Credentials.

1. Go to **Manage Jenkins → Credentials**
2. Add a credential of type **secret text**
3. Paste your SedStart API key
4. Give it an id
5. In Configuration, **Environment → Use secret text**
6. Bind it with variable name **SEDSTART_API_KEY**

---

## Usage in Freestyle Jobs

1. Add a **Build Step → SedStart Runner**
2. Choose **Run Mode** (Cloud or Local)
3. Fill in:
    - Project ID
    - Test ID or Suite ID
    - Profile ID
4. Provide the name of the environment variable that will contain the API key, Ideally Configured in Environment variables sections through secrets.

---


