pipeline{
  agent any
  stages{
    stage("Installing Maven"){
      steps{
        sh '''
        echo "Installing Maven 3.9.17"
        sudo rm -rf /opt/maven
        cd /tmp
        wget https://downloads.apache.org/maven/maven-3/3.9.12/binaries/apache-maven-3.9.12-bin.tar.gz
        tar -xzf apache-maven-3.9.12-bin.tar.gz
        sudo mv apache-maven-3.9.12 /opt/maven
        sudo chown -R jenkins:jenkins /opt/maven
        /opt/maven/bin/mvn -version
        '''
        
      }
    }
    stage("Checkout Code"){
      steps{
        git branch: 'main', url: 'https://github.com/sundar-s551/Test.git'
      }
    }
    stage("Build with maven"){
      steps{
        sh '/opt/maven/bin/mvn clean package -Dmaven.test.failure.ignore-true'
      }
    }
  }
  post{
    success{
      junit '**/target/surefire-reports/TEST-*.xml'
      archiveArtifacts '/target/*.jar'
      
    }
    failure{
      echo "Build Failed"
    }
  }
}
