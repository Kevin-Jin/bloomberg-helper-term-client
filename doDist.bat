CALL gradlew fatJar
IF NOT EXIST dist (mkdir dist)
move build\libs\bloomberg-helper-term-client-all-1.0.jar dist\bloomberg-helper-term-client.jar
java -jar dist/bloomberg-helper-term-client.jar
pause