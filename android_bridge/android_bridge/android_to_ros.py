import base64

from rclpy.time import Time
from sensor_msgs.msg import (
    BatteryState,
    CompressedImage,
    Imu,
    MagneticField,
    NavSatFix,
    NavSatStatus,
)


# Android device frame to ROS REP-103 FLU (landscape orientation)
def android_to_flu(x, y, z):
    return -z, y, x


def imu_msg(sample, frame_id):
    msg = Imu()
    msg.header.stamp = Time(nanoseconds=sample["stamp"]).to_msg()
    msg.header.frame_id = frame_id

    gx, gy, gz = android_to_flu(*sample["gyro"])
    msg.angular_velocity.x = float(gx)
    msg.angular_velocity.y = float(gy)
    msg.angular_velocity.z = float(gz)

    ax, ay, az = android_to_flu(*sample["accel"])
    msg.linear_acceleration.x = float(ax)
    msg.linear_acceleration.y = float(ay)
    msg.linear_acceleration.z = float(az)

    # -1 in the first element is the REP-145 for "no orientation estimate here"
    msg.orientation_covariance[0] = -1.0

    # TODO: Rough fixed covariances. Replace with values from a stationary Allan
    msg.angular_velocity_covariance[0] = 4e-4
    msg.angular_velocity_covariance[4] = 4e-4
    msg.angular_velocity_covariance[8] = 4e-4
    msg.linear_acceleration_covariance[0] = 4e-2
    msg.linear_acceleration_covariance[4] = 4e-2
    msg.linear_acceleration_covariance[8] = 4e-2

    return msg


def mag_msg(sample, frame_id):
    msg = MagneticField()
    msg.header.stamp = Time(nanoseconds=sample["stamp"]).to_msg()
    msg.header.frame_id = frame_id

    mx, my, mz = android_to_flu(*sample["mag"])
    msg.magnetic_field.x = float(mx)
    msg.magnetic_field.y = float(my)
    msg.magnetic_field.z = float(mz)

    return msg


def gps_msg(sample, frame_id):
    provider = sample.get("provider", "gps")

    msg = NavSatFix()
    msg.header.stamp = Time(nanoseconds=sample["stamp"]).to_msg()
    msg.header.frame_id = frame_id
    msg.status.status = NavSatStatus.STATUS_FIX
    msg.status.service = NavSatStatus.SERVICE_GPS if provider == "gps" else 0
    msg.latitude = float(sample["latitude"])
    msg.longitude = float(sample["longitude"])
    msg.altitude = float(sample["altitude"])

    horiz = float(sample.get("accuracy", 0.0)) ** 2
    vert = float(sample.get("vertical_accuracy", 0.0)) ** 2 or horiz
    msg.position_covariance[0] = horiz
    msg.position_covariance[4] = horiz
    msg.position_covariance[8] = vert
    msg.position_covariance_type = NavSatFix.COVARIANCE_TYPE_APPROXIMATED

    return msg


def frame_msg(sample, frame_id):
    msg = CompressedImage()
    msg.header.stamp = Time(nanoseconds=sample["stamp"]).to_msg()
    msg.header.frame_id = frame_id
    msg.format = "jpeg"
    msg.data = base64.b64decode(sample["data"])
    return msg


def battery_msg(sample):
    msg = BatteryState()
    msg.header.stamp = Time(nanoseconds=sample["stamp"]).to_msg()
    msg.voltage = float(sample.get("voltage", float("nan")))
    msg.temperature = float(sample.get("temperature", float("nan")))
    msg.current = float(sample.get("current", float("nan")))
    msg.percentage = float(sample.get("percentage", float("nan")))
    msg.present = bool(sample.get("present", True))

    msg.charge = float("nan")
    msg.capacity = float("nan")
    msg.design_capacity = float("nan")

    statuses = {
        "charging": BatteryState.POWER_SUPPLY_STATUS_CHARGING,
        "discharging": BatteryState.POWER_SUPPLY_STATUS_DISCHARGING,
        "not_charging": BatteryState.POWER_SUPPLY_STATUS_NOT_CHARGING,
        "full": BatteryState.POWER_SUPPLY_STATUS_FULL,
    }
    msg.power_supply_status = statuses.get(
        sample.get("status", "unknown"), BatteryState.POWER_SUPPLY_STATUS_UNKNOWN
    )

    healths = {
        "good": BatteryState.POWER_SUPPLY_HEALTH_GOOD,
        "overheat": BatteryState.POWER_SUPPLY_HEALTH_OVERHEAT,
        "dead": BatteryState.POWER_SUPPLY_HEALTH_DEAD,
        "overvoltage": BatteryState.POWER_SUPPLY_HEALTH_OVERVOLTAGE,
        "unspecified_failure": BatteryState.POWER_SUPPLY_HEALTH_UNSPEC_FAILURE,
        "cold": BatteryState.POWER_SUPPLY_HEALTH_COLD,
    }
    msg.power_supply_health = healths.get(
        sample.get("health", "unknown"), BatteryState.POWER_SUPPLY_HEALTH_UNKNOWN
    )

    technologies = {
        "nimh": BatteryState.POWER_SUPPLY_TECHNOLOGY_NIMH,
        "li-ion": BatteryState.POWER_SUPPLY_TECHNOLOGY_LION,
        "li-poly": BatteryState.POWER_SUPPLY_TECHNOLOGY_LIPO,
        "life": BatteryState.POWER_SUPPLY_TECHNOLOGY_LIFE,
        "nicd": BatteryState.POWER_SUPPLY_TECHNOLOGY_NICD,
        "limn": BatteryState.POWER_SUPPLY_TECHNOLOGY_LIMN,
    }
    msg.power_supply_technology = technologies.get(
        sample.get("technology", "unknown"),
        BatteryState.POWER_SUPPLY_TECHNOLOGY_UNKNOWN,
    )

    return msg
