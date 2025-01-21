# Incident Management

Welcome to the Incident Management reference sample application for CAP and development recommendations provided by the SAP BTP Developer Guide.

## Domain Model

The application support team members to create and process incidents on behalf of registered customers. The basic domain model is depicted below.

![domain drawio](xmpls/schema.drawio.svg)



## Setup

Assumed you prepared for CAP development as documented in capire's *[Getting Started > Jumpstart](https://cap.cloud.sap/docs/get-started/jumpstart)* page, ...

Clone the repository and install dependencies:

```sh
git clone https://github.com/cap-java/incidents-app
cd incidents-app
```

```sh
mvn clean compile
```


## Run

Run the application locally:

```sh
cd srv
mvn cds:watch
```
Then open http://localhost:8080/ . You can see all the default services and UI endpoints listed in the CAP default landing page. Navigate to [/incidents/webapp](http://localhost:8080/incidents/webapp/index.html). <br>
(login as `alice`, no password required).

<details>
    <summary> Troubleshooting </summary>
  If you get a 403 Forbidden Error and the logon popup doesn't show, try to open a browser in an incognito mode or clear the browser cache.
</details>


## Deploy

See: *[BTP Developer Guidelines Deployment Guides](https://help.sap.com/docs/btp/btp-developers-guide/deploy-cap)*


## Support, Feedback, Contributing

This project is open to feature requests/suggestions, bug reports etc. via [GitHub issues](https://github.com/SAP/<your-project>/issues). Contribution and feedback are encouraged and always welcome. For more information about how to contribute, the project structure, as well as additional contribution information, see our [Contribution Guidelines](CONTRIBUTING.md).

## Security / Disclosure
If you find any bug that may be a security problem, please follow our instructions at [in our security policy](https://github.com/SAP/<your-project>/security/policy) on how to report it. Please do not create GitHub issues for security-related doubts or problems.

## Code of Conduct

We as members, contributors, and leaders pledge to make participation in our community a harassment-free experience for everyone. By participating in this project, you agree to abide by its [Code of Conduct](https://github.com/SAP/.github/blob/main/CODE_OF_CONDUCT.md) at all times.

## Licensing

Copyright (20xx-)20xx SAP SE or an SAP affiliate company and <your-project> contributors. Please see our [LICENSE](LICENSE) for copyright and license information. Detailed information including third-party components and their licensing/copyright information is available [via the REUSE tool](https://api.reuse.software/info/github.com/SAP/<your-project>).
