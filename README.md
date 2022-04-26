# Project Loom C5M

Project Loom C5M is an experiment to achieve 5 million persistent connections each in client and server
Java applications using [OpenJDK Project Loom](https://openjdk.java.net/projects/loom/)
[virtual threads](https://openjdk.java.net/projects/loom/).

The C5M name is inspired by the [C10k problem](https://en.wikipedia.org/wiki/C10k_problem) proposed in 1999.

# Components

The project consists of two simple components, `EchoServer` and `EchoClient`.

`EchoServer` creates many TCP passive server sockets, accepting new connections on each as they come in.
For each active socket created, `EchoServer` receives bytes in and echoes them back out.

`EchoClient` initiates many outgoing TCP connections to a range of ports on a single destination server.
For each socket created, `EchoClient` sends a message to the server, awaits the responds, and going to sleep
for a time before sending again.

`EchoClient` terminates immediately if any of the follow occurs:
* Connection timeout
* Socket read timeout
* Integrity error with message received
* TCP connection closure
* TCP connection reset
* Any other unexpected I/O condition

# Experiments

After plenty of trial and error, I arrived at the following set of Linux kernel parameter changes
to support the target socket scale.

The following article about achieving 1 million persistent connections with Erlang was very helpful in this regard:
https://www.metabrew.com/article/a-million-user-comet-application-with-mochiweb-part-1

`/etc/sysctl.conf`:
```
fs.file-max=10485760
fs.nr_open=10485760

net.core.somaxconn=16192
net.core.netdev_max_backlog=16192
net.ipv4.tcp_max_syn_backlog=16192

net.ipv4.ip_local_port_range = 1024 65535

net.core.rmem_max = 16777216
net.core.wmem_max = 16777216
net.core.rmem_default = 16777216
net.core.wmem_default = 16777216
net.ipv4.tcp_rmem = 4096 87380 16777216
net.ipv4.tcp_wmem = 4096 87380 16777216
net.ipv4.tcp_mem = 1638400 1638400 1638400

net.netfilter.nf_conntrack_buckets = 1966050
net.netfilter.nf_conntrack_max = 7864200

#EC2 Amazon Linux
#net.core.netdev_max_backlog = 65536
#net.core.optmem_max = 25165824
#net.ipv4.tcp_max_tw_buckets = 1440000
```

`/etc/security/limits.conf`:
```
* soft nofile 8000000
* hard nofile 9000000
```

## Bare Metal

Two server-grade hosts were used for bare metal tests:

* HPE ProLiant BL460c Gen10
* 2x 20 core CPU
* 128GB RAM
* Xeon Gold 6230 CPUs
* CentOS 7.9
* Project Loom Early Access Build 19-loom+5-429 (2022/4/4) from https://jdk.java.net/loom/

The server launched with a passive server port range of [9000, 9099].

The client launched with the same server target port range and a connections-per-port count of 50,000, 
for a total of 5,000,000 target connections.

I stopped the experiment after about 40 minutes of continuous operation. None of the errors, closures, or timeouts 
outlined above occurred. 

---

The experiment ran for 40 minutes. About 200,000,000 messages were echoed.

Server:
```
[ebarlas@dct-kvm1.bmi ~]$ ./jdk-19/bin/java --enable-preview -ea -cp project-loom-scale-1.0.0-SNAPSHOT.jar loomtest.EchoServer 0.0.0.0 9000 100 16192 16
Args[host=0.0.0.0, port=9000, portCount=100, backlog=16192, bufferSize=16]
[0] connections=0, messages=0
[1040] connections=0, messages=0
...
[10047] connections=765, messages=765
[11047] connections=7292, messages=7282
...
[2422192] connections=5000000, messages=199113472
[2423193] connections=5000000, messages=199216494
[2424195] connections=5000000, messages=199304727
```

Client:
```
[ebarlas@dct-kvm2.bmi ~]$ ./jdk-19/bin/java --enable-preview -ea -cp project-loom-scale-1.0.0-SNAPSHOT.jar loomtest.EchoClient dct-kvm1.bmi.expertcity.com 9000 100 50000 60000 60000 60000
Args[host=dct-kvm1.bmi.expertcity.com, port=9000, portCount=100, numConnections=50000, socketTimeout=60000, warmUp=60000, sleep=60000]
[0] connections=8788, messages=8767
[1013] connections=25438, messages=25438
[2014] connections=45285, messages=45279
[3014] connections=64865, messages=64863
...
[2410230] connections=5000000, messages=199020253
[2411230] connections=5000000, messages=199092940
[2412230] connections=5000000, messages=199188157
```

---

The `ss` command reflects that both client and server had 5,000,000+ sockets open.

Server:
```
[ebarlas@dct-kvm1.bmi ~]$ ss -s
Total: 5001570 (kernel 0)
TCP:   5000123 (estab 5000010, closed 4, orphaned 0, synrecv 0, timewait 2/0), ports 0

Transport Total     IP        IPv6
*	  0         -         -        
RAW	  0         0         0        
UDP	  15        11        4        
TCP	  5000119   17        5000102  
INET	  5000134   28        5000106  
FRAG	  0         0         0 
```

Client:
```
[ebarlas@dct-kvm2.bmi ~]$ ss -s
Total: 5000735 (kernel 0)
TCP:   5000019 (estab 5000006, closed 5, orphaned 0, synrecv 0, timewait 4/0), ports 0

Transport Total     IP        IPv6
*	  0         -         -        
RAW	  0         0         0        
UDP	  12        8         4        
TCP	  5000014   13        5000001  
INET	  5000026   21        5000005  
FRAG	  0         0         0 
```

---

The server Java process used 32 GB of committed resident memory and 49 GB of virtual memory.
After running for 37m37s, it used 03h39m02s of CPU time.

The client Java process used 26 GB of committed resident memory and 49 GB of virtual memory.
After running for 37m12s, it used 06h16m30s of CPU time.


Server:
```
COMMAND                                                                                              %CPU     TIME     ELAPSED   PID %MEM   RSS    VSZ
./jdk-19/bin/java --enable-preview -ea -cp project-loom-scale-1.0.0-SNAPSHOT.jar loomtest.EchoServer  582 03:39:02       37:37 35102 24.1 31806188 48537940
```

Client:
```
COMMAND                                                                                              %CPU     TIME     ELAPSED   PID %MEM   RSS    VSZ
./jdk-19/bin/java --enable-preview -ea -cp project-loom-scale-1.0.0-SNAPSHOT.jar loomtest.EchoClient 1012 06:16:30       37:12 19339 20.0 26370892 48553472
```

## EC2

Two EC2 instances were used for bare metal tests:

* c5.2xlarge
* 16GB RAM
* 8 vCPU
* Amazon Linux 2 with Linux Kernel 5.10, AMI ami-00f7e5c52c0f43726
* Project Loom Early Access Build 19-loom+5-429 (2022/4/4) from https://jdk.java.net/loom/

The server launched with a passive server port range of [9000, 9049].

The client launched with the same server target port range and a connections-per-port count of 10,000,
for a total of 500,000 target connections.

I stopped the experiment after about 35 minutes of continuous operation. None of the errors, closures, or timeouts
outlined above occurred.

---

The experiment ran for 35 minutes. About 17,500,000 messages were echoed.

```
[ec2-user@ip-10-39-197-143 ~]$ ./jdk-19/bin/java --enable-preview -ea -cp project-loom-scale-1.0.0-SNAPSHOT.jar loomtest.EchoServer 0.0.0.0 9000 50 16192 16
Args[host=0.0.0.0, port=9000, portCount=50, backlog=16192, bufferSize=16]
[0] connections=0, messages=0
[1015] connections=0, messages=0
...
[17020] connections=2412, messages=2412
[18020] connections=10317, messages=10316
...
[2106644] connections=500000, messages=17414982
[2107645] connections=500000, messages=17423544
```

```
[ec2-user@ip-10-39-196-215 ~]$ ./jdk-19/bin/java --enable-preview -ea -cp project-loom-scale-1.0.0-SNAPSHOT.jar loomtest.EchoClient 10.39.197.143 9000 50 10000 30000 60000 60000
Args[host=10.39.197.143, port=9000, portCount=50, numConnections=10000, socketTimeout=30000, warmUp=60000, sleep=60000]
[0] connections=131, messages=114
[1014] connections=4949, messages=4949
[2014] connections=13248, messages=13246
...
[2091751] connections=500000, messages=17428105
[2092751] connections=500000, messages=17432046
```

---

The `ss` command reflects that both client and server had 500,000+ sockets open.

Server:
```
[ec2-user@ip-10-39-197-143 ~]$ ss -s
Total: 500233 (kernel 0)
TCP:   500057 (estab 500002, closed 0, orphaned 0, synrecv 0, timewait 0/0), ports 0

Transport Total     IP        IPv6
*	  0         -         -        
RAW	  0         0         0        
UDP	  8         4         4        
TCP	  500057    5         500052   
INET	  500065    9         500056   
FRAG	  0         0         0 
```

Client:
```
[ec2-user@ip-10-39-196-215 ~]$ ss -s
Total: 500183 (kernel 0)
TCP:   500007 (estab 500002, closed 0, orphaned 0, synrecv 0, timewait 0/0), ports 0

Transport Total     IP        IPv6
*	  0         -         -        
RAW	  0         0         0        
UDP	  8         4         4        
TCP	  500007    5         500002   
INET	  500015    9         500006   
FRAG	  0         0         0 
```

---

The server Java process used 2.3 GB of committed resident memory and 8.4 GB of virtual memory.
After running for 35.12m, it used 14m42s of CPU time.

The client Java process used 2.8 GB of committed resident memory and 8.9 GB of virtual memory.
After running for 34.88m, it used 25m19s of CPU time.


Server:
```
COMMAND                                                                                              %CPU     TIME   PID %MEM   RSS    VSZ
./jdk-19/bin/java --enable-preview -ea -cp project-loom-scale-1.0.0-SNAPSHOT.jar loomtest.EchoServer 36.7 00:12:42 18432 14.5 2317180 8434320
```

Client:
```
COMMAND                                                                                              %CPU     TIME   PID %MEM   RSS    VSZ
./jdk-19/bin/java --enable-preview -ea -cp project-loom-scale-1.0.0-SNAPSHOT.jar loomtest.EchoClient 73.9 00:25:19 18120 17.9 2848356 8901320
```