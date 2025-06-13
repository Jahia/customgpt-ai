# CustomGPT.ai Module

This repository contains a Jahia DX module integrating the [customGPT.ai](https://customgpt.ai) service. The module provides services and GraphQL endpoints to index Jahia content into customGPT and to keep the external index in sync when nodes are updated or deleted.

## Building

The project is built with Maven:

```bash
mvn clean package
```

The build requires the Jahia public Maven repository to resolve dependencies.

## Configuration

Default configuration properties are located under `src/main/resources/META-INF/configurations/org.jahia.community.modules.customgpt.cfg`. These values can be overridden using the OSGi configuration admin service once the module is deployed.

Key properties include:

- `org.jahia.community.modules.customgpt.projectId` – ID of your customGPT project.
- `org.jahia.community.modules.customgpt.token` – API token used to authenticate requests.
- Node types and file extensions that should be indexed.

## Usage

After deploying the module, pages and files matching the configured node types can be indexed or removed from the index using the provided GraphQL mutations or automatically through event listeners.

## License

This project is licensed under the terms of the Apache License Version 2.0. See the [LICENSE.txt](LICENSE.txt) file for details.
