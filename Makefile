.PHONY: resolver
resolver:
	java -jar ../buck/buck-out/gen/src/com/facebook/buck/maven/resolver.jar -json resolver.json -third-party third_party

.PHONY: autodeps
autodeps:
	buck run tools/clojure:buck-autodeps
