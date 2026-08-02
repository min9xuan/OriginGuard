.PHONY: bootstrap test compose-config

bootstrap:
	@echo "Run scripts/bootstrap.ps1 on Windows or install each workspace dependency explicitly."

test:
	@echo "Run frontend, Java and Python test suites after dependencies are installed."

compose-config:
	docker compose config

