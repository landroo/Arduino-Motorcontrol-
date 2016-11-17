/*
 Name:		Sketch1.ino
 Created:	10/11/2016 4:07:34 PM
 Author:	rkovacs
*/

//static int counter = 0;
int ledPin = 13;
// connect motor controller pins to Arduino digital pins
// motor one
int enA = 10;
int in1 = 9;
int in2 = 8;
// motor two
int enB = 5;
int in3 = 7;
int in4 = 6;
int velocity = 200;
int cmd = 0;

// the setup function runs once when you press reset or power the board
void setup() {
	Serial.begin(9600);
	pinMode(ledPin, OUTPUT);


}

// the loop function runs over and over again until power down or reset
void loop() {
	/*while(Serial.available() > 0) {
		Serial.write(Serial.read());
	}*/

	/*digitalWrite(ledPin, HIGH);
	delay(500);
	digitalWrite(ledPin, LOW);
	delay(500);*/

/*	Serial.print("Tick #");
	Serial.print(counter++, DEC);
	Serial.print("\n");

	if (Serial.peek() != -1) {
		Serial.print("Read: ");
		do {
			Serial.print((char)Serial.read());
		} while (Serial.peek() != -1);
		Serial.print("\n");
	}

	delay(1000);*/

	if (Serial.available()) {
		if (cmd == 55) {
			velocity = Serial.read();
			char cbuff[15];
			sprintf(cbuff, "speed: %d", velocity);
			Serial.print(cbuff);
		}
		cmd = Serial.read();
		switch (cmd) {
		case 49: // 1 - up start
			motorARight(velocity);
			Serial.print("motor A Right");
			break;
		case 50: // 2 - up/down stop
			stopMotorA();
			Serial.print("stop Motor A");
			break;
		case 51: // 3 - down start
			motorALeft(velocity);
			Serial.print("motor A Left");
			break;
		case 52: // 4 - left start
			motorBLeft(velocity);
			Serial.print("motor B Left");
			break;
		case 53: // 5 - left/right stop
			stopMotorB();
			Serial.print("stop Motor B");
			break;
		case 54: // 6 - right start
			motorBRight(velocity);
			Serial.print("motor B Right");
			break;
		case 55: // 7 - set speed
			Serial.print("set speed");
			break;
		case 57: // 9 - all stop
			allStop();
			Serial.print("all Stop");
			break;
		default:
			char cbuff[15];
			sprintf(cbuff, "read: %d", cmd);
			Serial.print(cbuff);
		}
	}


}

void motorARight(int speed) {
	// turn on motor A
	digitalWrite(in1, HIGH);
	digitalWrite(in2, LOW);
	// set speed to 200 out of possible range 0~255
	analogWrite(enA, speed);
}

void stopMotorA() {
	digitalWrite(in1, LOW);
	digitalWrite(in2, LOW);
}

void motorALeft(int speed) {
	// turn on motor A
	digitalWrite(in1, LOW);
	digitalWrite(in2, HIGH);
	// set speed to 200 out of possible range 0~255
	analogWrite(enA, speed);
}

void motorBRight(int speed) {
	// turn on motor B
	digitalWrite(in3, HIGH);
	digitalWrite(in4, LOW);
	// set speed to 200 out of possible range 0~255
	analogWrite(enB, 200);
}

void stopMotorB() {
	digitalWrite(in3, LOW);
	digitalWrite(in4, LOW);
}

void motorBLeft(int speed) {
	// turn on motor B
	digitalWrite(in3, LOW);
	digitalWrite(in4, HIGH);
	// set speed to 200 out of possible range 0~255
	analogWrite(enB, 200);
}

void allStop() {
	digitalWrite(in1, LOW);
	digitalWrite(in2, LOW);
	digitalWrite(in3, LOW);
	digitalWrite(in4, LOW);
}