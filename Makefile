JC = javac
JVM = java
JFLAGS = -g

.SUFFIXES: .java .class

.java.class:
	$(JC) $(JFLAGS) $*.java

CLASSES = \
	SendHost.java \
	ReceiverHost.java \
	TCPend.java

default: classes

classes: $(CLASSES:.java=.class)

clean:
	$(RM) *.class

