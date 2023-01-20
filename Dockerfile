FROM maven:latest
COPY src/ src
COPY pom.xml .
COPY run.sh .
RUN mvn compile
CMD ["sh", "run.sh"]